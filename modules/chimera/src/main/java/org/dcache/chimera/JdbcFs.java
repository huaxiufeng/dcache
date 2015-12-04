/*
 * This library is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Library General Public License as
 * published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Library General Public License for more details.
 *
 * You should have received a copy of the GNU Library General Public
 * License along with this program (see the file COPYING.LIB for more
 * details); if not, write to the Free Software Foundation, Inc.,
 * 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package org.dcache.chimera;

import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import diskCacheV111.util.AccessLatency;
import diskCacheV111.util.RetentionPolicy;
import org.dcache.acl.ACE;
import org.dcache.chimera.posix.Stat;
import org.dcache.chimera.store.InodeStorageInformation;
import org.dcache.util.Checksum;

import static org.dcache.commons.util.SqlHelper.*;

/**
 *
 * JDBC-FS is THE building block of Chimera. It's an abstraction layer, which
 * allows to build filesystem on top of a RDBMS.
 *
 *
 * @Immutable
 * @Threadsafe
 */
public class JdbcFs implements FileSystemProvider {
    /**
     * common error message for unimplemented
     */
    private static final String NOT_IMPL =
                    "this operation is unsupported for this "
                                    + "file system; please install a dCache-aware "
                                    + "implementation of the file system interface";

    /**
     * logger
     */
    private static final Logger _log = LoggerFactory.getLogger(JdbcFs.class);
    /**
     * the number of pnfs levels. Level zero associated with file real
     * content, which is not our regular case.
     */
    private static final int LEVELS_NUMBER = 7;
    private final RootInode _rootInode;
    private final String _wormID;

    /**
     * minimal binary handle size which can be processed.
    */
    private final static int MIN_HANDLE_LEN = 4;
    /**
     * SQL query engine
     */
    private final FsSqlDriver _sqlDriver;

    /**
     * Database connection pool
     */
    private final DataSource _dbConnectionsPool;

    /*
     * A dummy constant key force bay cache interface. the value doesn't
     * matter - only that it's the same value every time
     */
    private final Integer DUMMY_KEY = 0;
    /**
     * Cache value of FsStat
     */
    private final Executor _fsStatUpdateExecutor =
            Executors.newSingleThreadExecutor(
                    new ThreadFactoryBuilder()
                        .setNameFormat("fsstat-updater-thread-%d")
                        .build()
            );

    private final LoadingCache<Object, FsStat> _fsStatCache
            = CacheBuilder.newBuilder()
                .refreshAfterWrite(100, TimeUnit.MILLISECONDS)
                .build(
                    CacheLoader.asyncReloading(new CacheLoader<Object, FsStat>() {

                        @Override
                        public FsStat load(Object k) throws Exception {
                            return JdbcFs.this.getFsStat0();
                        }
                    }
            , _fsStatUpdateExecutor));

    /**
     * current fs id
     */
    private final int _fsId;
    /**
     * available space (1 Exabyte)
     */
    static final long AVAILABLE_SPACE = 1152921504606846976L;
    /**
     * total files
     */
    static final long TOTAL_FILES = 62914560L;

    /**
     * maximal length of an object name in a directory.
     */
    private final static int MAX_NAME_LEN = 255;

    public JdbcFs(DataSource dataSource, String dialect) {
        this(dataSource, dialect, 0);
    }

    public JdbcFs(DataSource dataSource, String dialect, int id) {

        _dbConnectionsPool = dataSource;
        _fsId = id;


        // try to get database dialect specific query engine
        _sqlDriver = FsSqlDriver.getDriverInstance(dialect);

        _rootInode = new RootInode(this);

        String wormID = null;
        try {
            wormID = getWormID().toString();
        } catch (Exception e) {
        }
        _wormID = wormID;
    }

    private FsInode getWormID() throws ChimeraFsException {

        return this.path2inode("/admin/etc/config");
    }

    //////////////////////////////////////////////////////////
    ////
    ////
    ////      Fs operations
    ////
    /////////////////////////////////////////////////////////
    @Override
    public FsInode createLink(String src, String dest) throws ChimeraFsException {

        File file = new File(src);
        return createLink(this.path2inode(file.getParent()), file.getName(), dest);
    }

    @Override
    public FsInode createLink(FsInode parent, String name, String dest) throws ChimeraFsException {
        return createLink(parent, name, 0, 0, 0644, dest.getBytes());
    }

    @Override
    public FsInode createLink(FsInode parent, String name, int uid, int gid, int mode, byte[] dest) throws ChimeraFsException {

        checkNameLength(name);

        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        FsInode inode;
        try {

            // read/write only
            dbConnection.setAutoCommit(false);

            if ((parent.statCache().getMode() & UnixPermission.S_ISGID) != 0) {
                gid = parent.statCache().getGid();
            }

            inode = _sqlDriver.createFile(dbConnection, parent, name, uid, gid, mode, UnixPermission.S_IFLNK);
            // link is a regular file where content is a reference
            _sqlDriver.setInodeIo(dbConnection, inode, true);
            _sqlDriver.write(dbConnection, inode, 0, 0, dest, 0, dest.length);

            dbConnection.commit();

        } catch (SQLException se) {
            tryToRollback(dbConnection);
            if (_sqlDriver.isDuplicatedKeyError(se)) {
                throw new FileExistsChimeraFsException();
            }
            _log.error("createLink ", se);
            throw new IOHimeraFsException(se.getMessage());
        } finally {
            tryToClose(dbConnection);
        }

        return inode;
    }

    /**
     *
     * create a hard link
     *
     * @param parent inode of directory where to create
     * @param inode
     * @param name
     * @return
     * @throws ChimeraFsException
     */
    @Override
    public FsInode createHLink(FsInode parent, FsInode inode, String name) throws ChimeraFsException {

        checkNameLength(name);

        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        try {

            // read/write only
            dbConnection.setAutoCommit(false);

            _sqlDriver.createEntryInParent(dbConnection, parent, name, inode);
            _sqlDriver.incNlink(dbConnection, inode);
            _sqlDriver.incNlink(dbConnection, parent);

            dbConnection.commit();

        } catch (SQLException e) {
            tryToRollback(dbConnection);

            if(_sqlDriver.isDuplicatedKeyError(e)) {
                throw new FileExistsChimeraFsException();
            }
            throw new IOHimeraFsException(e.getMessage());
        } finally {
            tryToClose(dbConnection);
        }

        return inode;
    }

    @Override
    public FsInode createFile(String path) throws ChimeraFsException {

        File file = new File(path);

        return this.createFile(this.path2inode(file.getParent()), file.getName());

    }

    @Override
    public FsInode createFile(FsInode parent, String name) throws ChimeraFsException {

        return createFile(parent, name, 0, 0, 0644);
    }

    @Override
    public FsInode createFileLevel(FsInode inode, int level) throws ChimeraFsException {
        return createFileLevel(inode, 0, 0, 0644, level);
    }

    @Override
    public FsInode createFile(FsInode parent, String name, int owner, int group, int mode) throws ChimeraFsException {
        return createFile(parent, name, owner, group, mode, UnixPermission.S_IFREG);
    }

    @Override
    public FsInode createFile(FsInode parent, String name, int owner, int group, int mode, int type) throws ChimeraFsException {

        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        FsInode inode = null;

        try {

            if (name.startsWith(".(")) { // special files only

                String[] cmd = PnfsCommandProcessor.process(name);

                if (name.startsWith(".(tag)(") && (cmd.length == 2)) {
                    this.createTag(parent, cmd[1], owner, group, 0644);
                    return new FsInode_TAG(this, parent.toString(), cmd[1]);
                }

                if (name.startsWith(".(pset)(") || name.startsWith(".(fset)(")) {
                    /**
                     * This is not 100% correct, as we throw exist even if
                     * someone tries to set attribute for a file which does not exist.
                     */
                    throw new FileExistsChimeraFsException(name);
                }

                if (name.startsWith(".(use)(") && (cmd.length == 3)) {
                    FsInode useInode = this.inodeOf(parent, cmd[2]);
                    int level = Integer.parseInt(cmd[1]);

                    try {
                        // read/write only
                        dbConnection.setAutoCommit(false);

                        inode = _sqlDriver.createLevel(dbConnection, useInode, useInode.stat().getUid(), useInode.stat().getGid(),
                                useInode.stat().getMode(), level);
                        dbConnection.commit();

                    } catch (SQLException se) {
                        tryToRollback(dbConnection);
                        if (_sqlDriver.isDuplicatedKeyError(se)) {
                            throw new FileExistsChimeraFsException(name);
                        }
                        _log.error("create File: ", se);
                    }
                }

                if (name.startsWith(".(access)(") && (cmd.length == 3)) {

                    FsInode accessInode = new FsInode(this, cmd[1]);
                    int accessLevel = Integer.parseInt(cmd[2]);
                    if (accessLevel == 0) {
                        inode = accessInode;
                    } else {
                        try {
                            // read/write only
                            dbConnection.setAutoCommit(false);

                            inode = _sqlDriver.createLevel(dbConnection, accessInode,
                                    accessInode.stat().getUid(), accessInode.stat().getGid(),
                                    accessInode.stat().getMode(), accessLevel);
                            dbConnection.commit();

                        } catch (SQLException se) {
                            tryToRollback(dbConnection);

                            if (_sqlDriver.isDuplicatedKeyError(se)) {
                                throw new FileExistsChimeraFsException(name);
                            }
                            _log.error("create File: ", se);
                        }
                    }
                }

                return inode;
            }

            try {

                checkNameLength(name);

                dbConnection.setAutoCommit(false);
                Stat parentStat = _sqlDriver.stat(dbConnection, parent);
                if (parentStat == null) {
                    throw new FileNotFoundHimeraFsException("parent=" + parent.toString());
                }

                if ((parentStat.getMode() & UnixPermission.F_TYPE) != UnixPermission.S_IFDIR) {
                    throw new NotDirChimeraException(parent);
                }

                if ((parentStat.getMode() & UnixPermission.S_ISGID) != 0) {
                    group = parent.statCache().getGid();
                }

                inode = _sqlDriver.createFile(dbConnection, parent, name, owner, group, mode, type);
                dbConnection.commit();

            } catch (SQLException se) {
                tryToRollback(dbConnection);

                if (_sqlDriver.isDuplicatedKeyError(se)) {
                    throw new FileExistsChimeraFsException();
                }
                _log.error("create File: ", se);
                throw new IOHimeraFsException(se.getMessage());
            }
        } finally {
            tryToClose(dbConnection);
        }


        return inode;
    }

    /**
     * Create a new entry with given inode id.
     *
     * @param parent
     * @param inode
     * @param name
     * @param owner
     * @param group
     * @param mode
     * @param type
     * @throws ChimeraFsException
     */
    @Override
    public void createFileWithId(FsInode parent, FsInode inode, String name, int owner, int group, int mode, int type) throws ChimeraFsException {

        checkNameLength(name);

        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        try {

            if (!parent.exists()) {
                throw new FileNotFoundHimeraFsException("parent=" + parent.toString());
            }

            if (parent.isDirectory()) {
                // read/write only
                dbConnection.setAutoCommit(false);

                if ((parent.statCache().getMode() & UnixPermission.S_ISGID) != 0) {
                    group = parent.statCache().getGid();
                }

                inode = _sqlDriver.createFileWithId(dbConnection, parent, inode, name, owner, group, mode, type);
                dbConnection.commit();

            } else {
                throw new NotDirChimeraException(parent);
            }

        } catch (SQLException se) {

            tryToRollback(dbConnection);

            if (_sqlDriver.isDuplicatedKeyError(se)) {
                throw new FileExistsChimeraFsException();
            }
            _log.error("create File: ", se);
            throw new IOHimeraFsException(se.getMessage());
        } finally {
            tryToClose(dbConnection);
        }
    }

    FsInode createFileLevel(FsInode inode, int owner, int group, int mode, int level) throws ChimeraFsException {
        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        // if exist table parent_dir create an entry
        FsInode levelInode = null;
        try {

            // read/write only
            dbConnection.setAutoCommit(false);

            levelInode = _sqlDriver.createLevel(dbConnection, inode, owner, group, mode | UnixPermission.S_IFREG, level);
            dbConnection.commit();

        } catch (SQLException se) {
            _log.error("create level: ", se);
            tryToRollback(dbConnection);
            throw new IOHimeraFsException(se.getMessage());
        } finally {
            tryToClose(dbConnection);
        }

        return levelInode;
    }

    public String[] listDir(String dir) {
        String[] list = null;

        try {
            list = this.listDir(this.path2inode(dir));
        } catch (Exception e) {
        }

        return list;
    }

    public String[] listDir(FsInode dir) throws IOHimeraFsException {

        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        String[] list = null;

        try {

            // read only
            dbConnection.setAutoCommit(true);

            list = _sqlDriver.listDir(dbConnection, dir);
        } catch (SQLException se) {
            _log.error("list: ", se);
            throw new IOHimeraFsException(se.getMessage());
        } finally {
            tryToClose(dbConnection);
        }

        return list;
    }

    @Override
    public DirectoryStreamB<HimeraDirectoryEntry> newDirectoryStream(FsInode dir) throws IOHimeraFsException {

        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        try {

            // read only
            dbConnection.setAutoCommit(true);

            return _sqlDriver.newDirectoryStream(dbConnection, dir);

        } catch (SQLException se) {
            _log.error("list full: ", se);
            tryToClose(dbConnection);
            throw new IOHimeraFsException(se.getMessage());
        }
        /*
         * Database resources are close by  DirectoryStreamB.close()
         */
    }

    @Override
    public void remove(String path) throws ChimeraFsException {

        File filePath = new File(path);

        String parentPath = filePath.getParent();
        if (parentPath == null) {
            throw new ChimeraFsException("Cannot delete file system root.");
        }

        FsInode parent = path2inode(parentPath);
        String name = filePath.getName();
        this.remove(parent, name);
    }

    @Override
    public void remove(FsInode parent, String name) throws ChimeraFsException {

        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        try {
            // read/write only
            dbConnection.setAutoCommit(false);

            _sqlDriver.remove(dbConnection, parent, name);
            dbConnection.commit();
        } catch (ChimeraFsException hfe) {
            tryToRollback(dbConnection);
            throw hfe;
        } catch (SQLException e) {
            _log.error("delete", e);
            tryToRollback(dbConnection);
            throw new BackEndErrorHimeraFsException(e.getMessage(), e);
        } finally {
            tryToClose(dbConnection);
        }
    }

    @Override
    public void remove(FsInode inode) throws ChimeraFsException {


        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        try {

            // read/write only
            dbConnection.setAutoCommit(false);

            FsInode parent = _sqlDriver.getParentOf(dbConnection, inode);
            if (parent == null) {
                throw new FileNotFoundHimeraFsException("No such file.");
            }

            if (inode.type() != FsInodeType.INODE) {
                // now allowed
                throw new FileNotFoundHimeraFsException("Not a file.");
            }

            _sqlDriver.remove(dbConnection, parent, inode);
            dbConnection.commit();
        } catch (ChimeraFsException hfe) {
            tryToRollback(dbConnection);
            throw hfe;
        } catch (SQLException e) {
            _log.error("delete", e);
            tryToRollback(dbConnection);
            throw new BackEndErrorHimeraFsException(e.getMessage(), e);
        } finally {
            tryToClose(dbConnection);
        }
    }

    @Override
    public Stat stat(String path) throws ChimeraFsException {
        return this.stat(this.path2inode(path));
    }

    @Override
    public Stat stat(FsInode inode) throws ChimeraFsException {
        return this.stat(inode, 0);
    }

    @Override
    public Stat stat(FsInode inode, int level) throws ChimeraFsException {

        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        Stat stat = null;

        try {

            // read only
            dbConnection.setAutoCommit(true);

            stat = _sqlDriver.stat(dbConnection, inode, level);

        } catch (SQLException e) {
            throw new IOHimeraFsException(e.getMessage());
        } finally {
            tryToClose(dbConnection);
        }

        if (stat == null) {
            throw new FileNotFoundHimeraFsException(inode.toString());
        }

        return stat;
    }

    @Override
    public FsInode mkdir(String path) throws ChimeraFsException {

        int li = path.lastIndexOf('/');
        String file = path.substring(li + 1);
        String dir;
        if (li > 1) {
            dir = path.substring(0, li);
        } else {
            dir = "/";
        }

        return this.mkdir(this.path2inode(dir), file);
    }

    @Override
    public FsInode mkdir(FsInode parent, String name) throws ChimeraFsException {
        return mkdir(parent, name, 0, 0, 0755);
    }

    @Override
    public FsInode mkdir(FsInode parent, String name, int owner, int group, int mode) throws ChimeraFsException {

        checkNameLength(name);

        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        FsInode inode = null;

        try {

            // read/write only
            dbConnection.setAutoCommit(false);

            if ((parent.statCache().getMode() & UnixPermission.S_ISGID) != 0) {
                group = parent.statCache().getGid();
                mode |= UnixPermission.S_ISGID;
            }

            inode = _sqlDriver.mkdir(dbConnection, parent, name, owner, group, mode);
            _sqlDriver.copyTags(dbConnection, parent, inode);
            dbConnection.commit();

        } catch (SQLException se) {

            tryToRollback(dbConnection);

            if (_sqlDriver.isDuplicatedKeyError(se)) {
                throw new FileExistsChimeraFsException(name);
            }
            _log.error("mkdir", se);
            throw new ChimeraFsException(se.getMessage());
        } finally {
            tryToClose(dbConnection);
        }

        return inode;
    }

    @Override
    public FsInode mkdir(FsInode parent, String name, int owner, int group, int mode,
                         List<ACE> acl, Map<String, byte[]> tags)
            throws ChimeraFsException
    {
        checkNameLength(name);

        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        FsInode inode = null;

        try {

            // read/write only
            dbConnection.setAutoCommit(false);

            if ((parent.statCache().getMode() & UnixPermission.S_ISGID) != 0) {
                group = parent.statCache().getGid();
                mode |= UnixPermission.S_ISGID;
            }

            inode = _sqlDriver.mkdir(dbConnection, parent, name, owner, group, mode, acl, tags);
            dbConnection.commit();
        } catch (SQLException se) {

            tryToRollback(dbConnection);

            if (_sqlDriver.isDuplicatedKeyError(se)) {
                throw new FileExistsChimeraFsException(name);
            }
            _log.error("mkdir", se);
            throw new ChimeraFsException(se.getMessage());
        } finally {
            tryToClose(dbConnection);
        }

        return inode;
    }

    @Override
    public FsInode path2inode(String path) throws ChimeraFsException {
        return path2inode(path, new RootInode(this));
    }

    @Override
    public FsInode path2inode(String path, FsInode startFrom) throws ChimeraFsException {

        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        FsInode inode = null;

        try {

            dbConnection.setAutoCommit(true);
            inode = _sqlDriver.path2inode(dbConnection, startFrom, path);

            if (inode == null) {
                throw new FileNotFoundHimeraFsException(path);
            }

        } catch (SQLException e) {
            _log.error("path2inode", e);
            throw new IOHimeraFsException(e.getMessage());
        } finally {
            tryToClose(dbConnection);
        }

        return inode;
    }

    @Override
    public List<FsInode> path2inodes(String path) throws ChimeraFsException
    {
        return path2inodes(path, new RootInode(this));
    }

    @Override
    public List<FsInode> path2inodes(String path, FsInode startFrom)
        throws ChimeraFsException
    {
        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        List<FsInode> inodes;

        try {
            dbConnection.setAutoCommit(true);
            inodes = _sqlDriver.path2inodes(dbConnection, startFrom, path);

            if (inodes.isEmpty()) {
                throw new FileNotFoundHimeraFsException(path);
            }
        } catch (SQLException e) {
            _log.error("path2inode", e);
            throw new IOHimeraFsException(e.getMessage());
        } finally {
            tryToClose(dbConnection);
        }

        return inodes;
    }

    @Override
    public FsInode inodeOf(FsInode parent, String name) throws ChimeraFsException {
        FsInode inode = null;

        // only if it's PNFS command
        if (name.startsWith(".(")) {

            if (name.startsWith(".(id)(")) {
                String[] cmd = PnfsCommandProcessor.process(name);
                if (cmd.length != 2) {
                    throw new FileNotFoundHimeraFsException(name);
                }
                inode = inodeOf(parent, cmd[1]);

                return new FsInode_ID(this, inode.toString());

            }

            if (name.startsWith(".(use)(")) {
                String[] cmd = PnfsCommandProcessor.process(name);
                if (cmd.length != 3) {
                    throw new FileNotFoundHimeraFsException(name);
                }
                try {
                    int level = Integer.parseInt(cmd[1]);

                    FsInode useInode = this.inodeOf(parent, cmd[2]);

                    if (level <= LEVELS_NUMBER) {
                        this.stat(useInode, level);
                        return new FsInode(this, useInode.toString(), level);
                    } else {
                        // is it error or a real file?
                    }
                } catch (NumberFormatException nfe) {
                    //	possible wrong format...ignore
                } catch (FileNotFoundHimeraFsException e) {
                    throw new FileNotFoundHimeraFsException(name);
                }

            }

            if (name.startsWith(".(access)(")) {
                String[] cmd = PnfsCommandProcessor.process(name);
                if ((cmd.length < 2) || (cmd.length > 3)) {
                    throw new FileNotFoundHimeraFsException(name);
                }
                try {
                    int level = cmd.length == 2 ? 0 : Integer.parseInt(cmd[2]);

                    FsInode useInode = new FsInode(this, cmd[1]);

                    if (level <= LEVELS_NUMBER) {
                        this.stat(useInode, level);
                        return new FsInode(this, useInode.toString(), level);
                    } else {
                        // is it error or a real file?
                    }
                } catch (NumberFormatException nfe) {
                    //	possible wrong format...ignore
                } catch (FileNotFoundHimeraFsException e) {
                    throw new FileNotFoundHimeraFsException(name);
                }

            }

            if (name.startsWith(".(nameof)(")) {
                String[] cmd = PnfsCommandProcessor.process(name);
                if (cmd.length != 2) {
                    throw new FileNotFoundHimeraFsException(name);
                }
                FsInode nameofInode = new FsInode_NAMEOF(this, cmd[1]);
                if (!nameofInode.exists()) {
                    throw new FileNotFoundHimeraFsException(name);
                }
                return nameofInode;
            }

            if (name.startsWith(".(const)(")) {
                String[] cmd = PnfsCommandProcessor.process(name);
                if (cmd.length != 2) {
                    throw new FileNotFoundHimeraFsException(name);
                }
                FsInode constInode = new FsInode_CONST(this, parent.toString());
                if (!constInode.exists()) {
                    throw new FileNotFoundHimeraFsException(name);
                }
                return constInode;
            }

            if (name.startsWith(".(parent)(")) {
                String[] cmd = PnfsCommandProcessor.process(name);
                if (cmd.length != 2) {
                    throw new FileNotFoundHimeraFsException(name);
                }
                FsInode parentInode = new FsInode_PARENT(this, cmd[1]);
                if (!parentInode.exists()) {
                    throw new FileNotFoundHimeraFsException(name);
                }
                return parentInode;
            }

            if (name.startsWith(".(pathof)(")) {
                String[] cmd = PnfsCommandProcessor.process(name);
                if (cmd.length != 2) {
                    throw new FileNotFoundHimeraFsException(name);
                }
                FsInode pathofInode = new FsInode_PATHOF(this, cmd[1]);
                if (!pathofInode.exists()) {
                    throw new FileNotFoundHimeraFsException(name);
                }
                return pathofInode;
            }

            if (name.startsWith(".(tag)(")) {
                String[] cmd = PnfsCommandProcessor.process(name);
                if (cmd.length != 2) {
                    throw new FileNotFoundHimeraFsException(name);
                }
                FsInode tagInode = new FsInode_TAG(this, parent.toString(), cmd[1]);
                if (!tagInode.exists()) {
                    throw new FileNotFoundHimeraFsException(name);
                }
                return tagInode;
            }

            if (name.equals(".(tags)()")) {
                return new FsInode_TAGS(this, parent.toString());
            }

            if (name.startsWith(".(pset)(")) {
                String[] cmd = PnfsCommandProcessor.process(name);
                if (cmd.length < 3) {
                    throw new FileNotFoundHimeraFsException(name);
                }
                String[] args = new String[cmd.length - 2];
                System.arraycopy(cmd, 2, args, 0, args.length);
                FsInode psetInode = new FsInode_PSET(this, cmd[1], args);
                if (!psetInode.exists()) {
                    throw new FileNotFoundHimeraFsException(name);
                }
                return psetInode;
            }

            if (name.equals(".(get)(cursor)")) {
                FsInode pgetInode = new FsInode_PCUR(this, parent.toString());
                if (!pgetInode.exists()) {
                    throw new FileNotFoundHimeraFsException(name);
                }
                return pgetInode;
            }

            if (name.startsWith(".(get)(")) {
                String[] cmd = PnfsCommandProcessor.process(name);
                if (cmd.length < 3) {
                    throw new FileNotFoundHimeraFsException(name);
                }

                inode = inodeOf(parent, cmd[1]);
                if (!inode.exists()) {
                    throw new FileNotFoundHimeraFsException(name);
                }

                switch(cmd[2]) {
                    case "locality":
                        return new FsInode_PLOC(this, inode.toString());
                    case "checksum":
                    case "checksums":
                        return new FsInode_PCRC(this, inode.toString());
                    default:
                        throw new ChimeraFsException
                            ("unsupported argument for .(get) " + cmd[2]);
                }
            }

            if (name.equals(".(config)")) {
                return new FsInode(this, _wormID);
            }

            if (name.startsWith(".(config)(")) {
                String[] cmd = PnfsCommandProcessor.process(name);
                if (cmd.length != 2) {
                    throw new FileNotFoundHimeraFsException(name);
                }
                return this.inodeOf(new FsInode(this, _wormID), cmd[1]);
            }

            if (name.startsWith(".(fset)(")) {
                String[] cmd = PnfsCommandProcessor.process(name);
                if (cmd.length < 3) {
                    throw new FileNotFoundHimeraFsException(name);
                }
                String[] args = new String[cmd.length - 2];
                System.arraycopy(cmd, 2, args, 0, args.length);

                FsInode fsetInode = this.inodeOf(parent, cmd[1]);
                if (!fsetInode.exists()) {
                    throw new FileNotFoundHimeraFsException(name);
                }
                return new FsInode_PSET(this, fsetInode.toString(), args);
            }

        }

        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        try {
            // read only
            dbConnection.setAutoCommit(true);

            inode = _sqlDriver.inodeOf(dbConnection, parent, name);

            if (inode == null) {
                throw new FileNotFoundHimeraFsException(name);
            }

        } catch (SQLException e) {
            _log.error("inodeOf", e);
            throw new IOHimeraFsException(e.getMessage());
        } finally {
            tryToClose(dbConnection);
        }

        inode.setParent(parent);

        return inode;
    }

    @Override
    public String inode2path(FsInode inode) throws ChimeraFsException {
        return inode2path(inode, new RootInode(this), true);
    }

    /**
     *
     * @param inode
     * @param startFrom
     * @return path of inode starting from startFrom
     * @throws ChimeraFsException
     */
    @Override
    public String inode2path(FsInode inode, FsInode startFrom, boolean inclusive) throws ChimeraFsException {

        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        String path = null;

        try {
            // read only
            dbConnection.setAutoCommit(true);

            path = _sqlDriver.inode2path(dbConnection, inode, startFrom, inclusive);

        } catch (SQLException e) {
            _log.error("inode2path", e);
            throw new IOHimeraFsException(e.getMessage());
        } finally {
            tryToClose(dbConnection);
        }

        return path;

    }

    @Override
    public boolean removeFileMetadata(String path, int level) throws ChimeraFsException {

        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        boolean rc = false;

        try {

            // read/write only
            dbConnection.setAutoCommit(false);

            rc = _sqlDriver.removeInodeLevel(dbConnection, this.path2inode(path), level);
            dbConnection.commit();
        } catch (SQLException e) {
            _log.error("removeFileMetadata", e);
            tryToRollback(dbConnection);
            throw new IOHimeraFsException(e.getMessage());
        } finally {
            tryToClose(dbConnection);
        }

        return rc;
    }

    @Override
    public FsInode getParentOf(FsInode inode) throws ChimeraFsException {

        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        FsInode parent = null;

        try {
            // read only
            dbConnection.setAutoCommit(true);

            if (inode.isDirectory()) {
                parent = _sqlDriver.getParentOfDirectory(dbConnection, inode);
            } else {
                parent = _sqlDriver.getParentOf(dbConnection, inode);
            }

        } catch (SQLException e) {
            _log.error("getPathOf", e);
            throw new IOHimeraFsException(e.getMessage());
        } finally {
            tryToClose(dbConnection);
        }

        return parent;
    }

    @Override
    public void setFileName(FsInode dir, String oldName, String newName) throws ChimeraFsException {
	move(dir, oldName, dir, newName);
    }

    @Override
    public void setInodeAttributes(FsInode inode, int level, Stat stat) throws ChimeraFsException {

        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        try {
            // read/write only
            dbConnection.setAutoCommit(false);

            switch (inode.type()) {
                case INODE:
		case PSET:
                    boolean applied = _sqlDriver.setInodeAttributes(dbConnection, inode, level, stat);
                    if (!applied) {
                        /**
                         * there are two cases why update can fail: 1. inode
                         * does not exists 2. we try to set a size on a non file
                         * object
                         */
                        Stat s = _sqlDriver.stat(dbConnection, inode);
                        if (s == null) {
                            throw new FileNotFoundHimeraFsException();
                        }
                        if ((s.getMode() & UnixPermission.F_TYPE) == UnixPermission.S_IFDIR) {
                            throw new IsDirChimeraException(inode);
                        }
                        throw new InvalidArgumentChimeraException();
                    }
                    break;
                case TAG:
                    if (stat.isDefined(Stat.StatAttributes.MODE)) {
                        _sqlDriver.setTagMode(dbConnection, (FsInode_TAG) inode, stat.getMode());
                    }
                    if (stat.isDefined(Stat.StatAttributes.UID)) {
                        _sqlDriver.setTagOwner(dbConnection, (FsInode_TAG) inode, stat.getUid());
                    }
                    if (stat.isDefined(Stat.StatAttributes.GID)) {
                        _sqlDriver.setTagOwnerGroup(dbConnection, (FsInode_TAG) inode, stat.getGid());
                    }
                    break;
            }
            dbConnection.commit();

        } catch (SQLException e) {
            _log.error("setInodeAttributes", e);
            tryToRollback(dbConnection);
            throw new IOHimeraFsException(e.getMessage());
        } finally {
            tryToClose(dbConnection);
        }
    }

    @Override
    public boolean isIoEnabled(FsInode inode) throws ChimeraFsException {

        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        boolean ioEnabled = false;

        try {

            // read only
            dbConnection.setAutoCommit(true);

            ioEnabled = _sqlDriver.isIoEnabled(dbConnection, inode);

        } catch (SQLException e) {
            _log.error("isIoEnabled", e);
            throw new IOHimeraFsException(e.getMessage());
        } finally {
            tryToClose(dbConnection);
        }

        return ioEnabled;

    }

    @Override
    public void setInodeIo(FsInode inode, boolean enable) throws ChimeraFsException {

        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        try {
            // read/write only
            dbConnection.setAutoCommit(false);

            _sqlDriver.setInodeIo(dbConnection, inode, enable);
            dbConnection.commit();
        } catch (SQLException e) {
            _log.error("setInodeIo", e);
            tryToRollback(dbConnection);
            throw new IOHimeraFsException(e.getMessage());
        } finally {
            tryToClose(dbConnection);
        }
    }

    public int write(FsInode inode, long beginIndex, byte[] data, int offset, int len) throws ChimeraFsException {
        return this.write(inode, 0, beginIndex, data, offset, len);
    }

    @Override
    public int write(FsInode inode, int level, long beginIndex, byte[] data, int offset, int len) throws ChimeraFsException {


        if (level == 0 && !inode.isIoEnabled()) {
            _log.debug(inode + ": IO (write) not allowd");
            return -1;
        }

        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        try {
            // read/write only
            dbConnection.setAutoCommit(false);

            _sqlDriver.write(dbConnection, inode, level, beginIndex, data, offset, len);
            dbConnection.commit();
        } catch (SQLException e) {
            tryToRollback(dbConnection);

            if (_sqlDriver.isForeignKeyError(e)) {
                throw new FileNotFoundHimeraFsException();
            }
            _log.error("write", e);
            throw new IOHimeraFsException(e.getMessage());
        } finally {
            tryToClose(dbConnection);
        }

        return len;
    }

    public int read(FsInode inode, long beginIndex, byte[] data, int offset, int len) throws ChimeraFsException {
        return this.read(inode, 0, beginIndex, data, offset, len);
    }

    @Override
    public int read(FsInode inode, int level, long beginIndex, byte[] data, int offset, int len) throws ChimeraFsException {

        int count = -1;

        if (level == 0 && !inode.isIoEnabled()) {
            _log.debug(inode + ": IO(read) not allowd");
            return -1;
        }

        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        try {
            // read only
            dbConnection.setAutoCommit(true);

            count = _sqlDriver.read(dbConnection, inode, level, beginIndex, data, offset, len);

        } catch (SQLException se) {
            _log.debug("read:", se);
            throw new IOHimeraFsException(se.getMessage());
        } catch (IOException e) {
            _log.debug("read IO:", e);
        } finally {
            tryToClose(dbConnection);
        }

        return count;
    }

    @Override
    public byte[] readLink(String path) throws ChimeraFsException {
        return this.readLink(this.path2inode(path));
    }

    @Override
    public byte[] readLink(FsInode inode) throws ChimeraFsException {

        byte[] link;
        byte[] b = new byte[(int) inode.statCache().getSize()];

        int n = this.read(inode, 0, b, 0, b.length);
        if (n >= 0) {
            link = b;
        } else {
            link = new byte[0];
        }

        return link;
    }

    @Override
    public boolean move(String source, String dest) {
        boolean rc;

        try {

            File what = new File(source);
            File where = new File(dest);

            rc = this.move(this.path2inode(what.getParent()), what.getName(), this.path2inode(where.getParent()), where.getName());

        } catch (Exception e) {
            rc = false;
        }

        return rc;
    }

    @Override
    public boolean move(FsInode srcDir, String source, FsInode destDir, String dest) throws ChimeraFsException {

        checkNameLength(dest);

        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        try {

            // read/write only
            dbConnection.setAutoCommit(false);

            Stat destStat = _sqlDriver.stat(dbConnection, destDir);
            if ((destStat.getMode() & UnixPermission.F_TYPE) != UnixPermission.S_IFDIR) {
                throw new NotDirChimeraException(destDir);
            }

            FsInode destInode = _sqlDriver.inodeOf(dbConnection, destDir, dest);
            FsInode srcInode = _sqlDriver.inodeOf(dbConnection, srcDir, source);
            if (srcInode == null) {
                throw new FileNotFoundHimeraFsException(source);
            }

            if (destInode != null) {
                Stat statDest = _sqlDriver.stat(dbConnection, destInode);
                Stat statSrc = _sqlDriver.stat(dbConnection, srcInode);
                if (destInode.equals(srcInode)) {
                   // according to POSIX, we are done
                   dbConnection.commit();
                   return false;
                }

               /*
                * renaming only into existing same type is allowed
                */
                if ((statSrc.getMode() & UnixPermission.S_TYPE) != (statDest.getMode() & UnixPermission.S_TYPE)) {
                    throw new FileExistsChimeraFsException(dest);
                }

                _sqlDriver.remove(dbConnection, destDir, dest);
            }

            if (!srcDir.equals(destDir)) {
                _sqlDriver.move(dbConnection, srcDir, source, destDir, dest);
                _sqlDriver.incNlink(dbConnection, destDir);
                _sqlDriver.decNlink(dbConnection, srcDir);
            } else {
                // same directory
		long now = System.currentTimeMillis();
		_sqlDriver.setFileName(dbConnection, srcDir, source, dest);
		Stat stat = new Stat();
		stat.setMTime(now);
		_sqlDriver.setInodeAttributes(dbConnection, destDir, 0, stat);
            }

            dbConnection.commit();
        } catch (SQLException e) {
            _log.error("move:", e);
            tryToRollback(dbConnection);
            throw new IOHimeraFsException(e.getMessage());
        } finally {
            tryToClose(dbConnection);
        }

        return true;
    }

    /////////////////////////////////////////////////////////////////////
    ////
    ////   Location info
    ////
    ////////////////////////////////////////////////////////////////////
    @Override
    public List<StorageLocatable> getInodeLocations(FsInode inode, int type) throws ChimeraFsException {

        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        List<StorageLocatable> locations = null;

        try {

            // read/write only
            dbConnection.setAutoCommit(true);

            locations = _sqlDriver.getInodeLocations(dbConnection, inode, type);

        } catch (SQLException se) {
            _log.error("getInodeLocations", se);
            throw new IOHimeraFsException(se.getMessage());
        } finally {
            tryToClose(dbConnection);
        }

        return locations;
    }

    @Override
    public List<StorageLocatable> getInodeLocations(FsInode inode) throws ChimeraFsException {

        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        List<StorageLocatable> locations = null;

        try {

            // read/write only
            dbConnection.setAutoCommit(true);

            locations = _sqlDriver.getInodeLocations(dbConnection, inode);

        } catch (SQLException se) {
            _log.error("getInodeLocations", se);
            throw new IOHimeraFsException(se.getMessage());
        } finally {
            tryToClose(dbConnection);
        }

        return locations;
    }

    @Override
    public void addInodeLocation(FsInode inode, int type, String location) throws ChimeraFsException {

        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        try {
            // read/write only
            dbConnection.setAutoCommit(false);

            _sqlDriver.addInodeLocation(dbConnection, inode, type, location);
            dbConnection.commit();
        } catch (SQLException se) {
            tryToRollback(dbConnection);

            if (_sqlDriver.isForeignKeyError(se)) {
                throw new FileNotFoundHimeraFsException();
            }

            if (!_sqlDriver.isDuplicatedKeyError(se)) {
                _log.error("addInodeLocation:", se);
                throw new IOHimeraFsException(se.getMessage());
            }
        } finally {
            tryToClose(dbConnection);
        }
    }

    @Override
    public void clearInodeLocation(FsInode inode, int type, String location) throws ChimeraFsException {

        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        try {
            // read/write only
            dbConnection.setAutoCommit(false);

            _sqlDriver.clearInodeLocation(dbConnection, inode, type, location);
            dbConnection.commit();
        } catch (SQLException se) {
            _log.error("clearInodeLocation", se);
            tryToRollback(dbConnection);
            throw new IOHimeraFsException(se.getMessage());
        } finally {
            tryToClose(dbConnection);
        }
    }

    /////////////////////////////////////////////////////////////////////
    ////
    ////   Directory tags handling
    ////
    ////////////////////////////////////////////////////////////////////
    @Override
    public String[] tags(FsInode inode) throws ChimeraFsException {
        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        String[] list = null;
        try {
            // read/write only
            dbConnection.setAutoCommit(true);

            list = _sqlDriver.tags(dbConnection, inode);

        } catch (SQLException se) {
            _log.error("tags", se);
            throw new IOHimeraFsException(se.getMessage());
        } finally {
            tryToClose(dbConnection);
        }

        return list;
    }

    @Override
    public Map<String, byte[]> getAllTags(FsInode inode) throws ChimeraFsException {
        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        try {
            // read only
            dbConnection.setAutoCommit(true);
            return _sqlDriver.getAllTags(dbConnection, inode);
        } catch (SQLException | IOException e) {
            _log.error("getAllTags", e);
            throw new IOHimeraFsException(e.getMessage(), e);
        } finally {
            tryToClose(dbConnection);
        }
    }

    @Override
    public void createTag(FsInode inode, String name) throws ChimeraFsException {
        this.createTag(inode, name, 0, 0, 0644);
    }

    @Override
    public void createTag(FsInode inode, String name, int uid, int gid, int mode) throws ChimeraFsException {
        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        try {
            // read/write only
            dbConnection.setAutoCommit(false);

            _sqlDriver.createTag(dbConnection, inode, name, uid, gid, mode);
            dbConnection.commit();
        } catch (SQLException e) {
            tryToRollback(dbConnection);
            if (_sqlDriver.isDuplicatedKeyError(e)) {
                throw new FileExistsChimeraFsException();
            }
            _log.error("createTag", e);
            throw new IOHimeraFsException(e.getMessage());
        } finally {
            tryToClose(dbConnection);
        }
    }

    @Override
    public int setTag(FsInode inode, String tagName, byte[] data, int offset, int len) throws ChimeraFsException {
        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        try {
            // read/write only
            dbConnection.setAutoCommit(false);

            _sqlDriver.setTag(dbConnection, inode, tagName, data, offset, len);
            dbConnection.commit();
        } catch (SQLException e) {
            _log.error("setTag", e);
            tryToRollback(dbConnection);
            throw new IOHimeraFsException(e.getMessage());
        } catch (ChimeraFsException e) {
            _log.error("setTag", e);
        } finally {
            tryToClose(dbConnection);
        }

        return len;

    }

    @Override
    public void removeTag(FsInode dir, String tagName) throws ChimeraFsException
    {
        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        try {
            // read/write only
            dbConnection.setAutoCommit(false);

            _sqlDriver.removeTag(dbConnection, dir, tagName);
            dbConnection.commit();
        } catch (SQLException e) {
            _log.error("removeTag", e);
            tryToRollback(dbConnection);
            throw new IOHimeraFsException(e.getMessage());
        } finally {
            tryToClose(dbConnection);
        }
    }

    @Override
    public void removeTag(FsInode dir) throws ChimeraFsException {
        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        try {
            // read/write only
            dbConnection.setAutoCommit(false);

            _sqlDriver.removeTag(dbConnection, dir);
            dbConnection.commit();
        } catch (SQLException e) {
            _log.error("removeTag", e);
            tryToRollback(dbConnection);
            throw new IOHimeraFsException(e.getMessage());
        } finally {
            tryToClose(dbConnection);
        }
    }

    @Override
    public int getTag(FsInode inode, String tagName, byte[] data, int offset, int len) throws ChimeraFsException {
        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        int count = -1;

        try {
            // read only
            dbConnection.setAutoCommit(true);

            count = _sqlDriver.getTag(dbConnection, inode, tagName, data, offset, len);

        } catch (SQLException e) {

            _log.error("getTag", e);
            throw new IOHimeraFsException(e.getMessage());
        } catch (IOException e) {
            _log.error("getTag io", e);
        } finally {
            tryToClose(dbConnection);
        }

        return count;
    }

    @Override
    public Stat statTag(FsInode dir, String name) throws ChimeraFsException {
        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }


        Stat ret = null;

        try {

            // read only
            dbConnection.setAutoCommit(true);

            ret = _sqlDriver.statTag(dbConnection, dir, name);

        } catch (SQLException e) {
            _log.error("statTag", e);
            throw new IOHimeraFsException(e.getMessage());
        } finally {
            tryToClose(dbConnection);
        }

        return ret;
    }

    @Override
    public void setTagOwner(FsInode_TAG tagInode, String name, int owner) throws ChimeraFsException {
        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        try {
            dbConnection.setAutoCommit(false);

            _sqlDriver.setTagOwner(dbConnection, tagInode, owner);
            dbConnection.commit();
        } catch (SQLException e) {
            _log.error("setTagOwner", e);
            tryToRollback(dbConnection);
            throw new IOHimeraFsException(e.getMessage());
        } finally {
            tryToClose(dbConnection);
        }
    }

    @Override
    public void setTagOwnerGroup(FsInode_TAG tagInode, String name, int owner) throws ChimeraFsException {
        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        try {
            dbConnection.setAutoCommit(false);

            _sqlDriver.setTagOwnerGroup(dbConnection, tagInode, owner);
            dbConnection.commit();
        } catch (SQLException e) {
            _log.error("setTagOwnerGroup", e);
            tryToRollback(dbConnection);
            throw new IOHimeraFsException(e.getMessage());
        } finally {
            tryToClose(dbConnection);
        }
    }

    @Override
    public void setTagMode(FsInode_TAG tagInode, String name, int mode) throws ChimeraFsException {
        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        try {
            dbConnection.setAutoCommit(false);

            _sqlDriver.setTagMode(dbConnection, tagInode, mode);
            dbConnection.commit();
        } catch (SQLException e) {
            _log.error("setTagMode", e);
            tryToRollback(dbConnection);
            throw new IOHimeraFsException(e.getMessage());
        } finally {
            tryToClose(dbConnection);
        }
    }

    ///////////////////////////////////////////////////////////////
    //
    // Id and Co.
    //
    @Override
    public int getFsId() {
        return _fsId;
    }

    /*
     * Storage Information
     *
     * currently it's not allowed to modify it
     */
    @Override
    public void setStorageInfo(FsInode inode, InodeStorageInformation storageInfo) throws ChimeraFsException {
        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        try {
            // read/write only
            dbConnection.setAutoCommit(false);

            _sqlDriver.setStorageInfo(dbConnection, inode, storageInfo);
            dbConnection.commit();
        } catch (SQLException se) {
            tryToRollback(dbConnection);

            if (_sqlDriver.isForeignKeyError(se)) {
                throw new FileNotFoundHimeraFsException();
            }

            if (!_sqlDriver.isDuplicatedKeyError(se)) {
                _log.error("setStorageInfo:", se);
                throw new IOHimeraFsException(se.getMessage());
            }
        } finally {
            tryToClose(dbConnection);
        }
    }

    /**
     *
     * @param inode
     * @param accessLatency
     * @throws ChimeraFsException
     */
    @Override
    public void setAccessLatency(FsInode inode, AccessLatency accessLatency) throws ChimeraFsException {
        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        try {
            // read/write only
            dbConnection.setAutoCommit(false);

            _sqlDriver.setAccessLatency(dbConnection, inode, accessLatency);
            dbConnection.commit();
        } catch (SQLException e) {
            tryToRollback(dbConnection);

            if (_sqlDriver.isForeignKeyError(e)) {
                throw new FileNotFoundHimeraFsException();
            }
            _log.error("setAccessLatency:", e);
            throw new IOHimeraFsException(e.getMessage());
        } finally {
            tryToClose(dbConnection);
        }
    }

    @Override
    public void setRetentionPolicy(FsInode inode, RetentionPolicy retentionPolicy) throws ChimeraFsException {
        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        try {
            // read/write only
            dbConnection.setAutoCommit(false);

            _sqlDriver.setRetentionPolicy(dbConnection, inode, retentionPolicy);
            dbConnection.commit();
        } catch (SQLException e) {
            tryToRollback(dbConnection);

            if (_sqlDriver.isForeignKeyError(e)) {
                throw new FileNotFoundHimeraFsException();
            }
            _log.error("setRetentionPolicy:", e);
            throw new IOHimeraFsException(e.getMessage());
        } finally {
            tryToClose(dbConnection);
        }
    }

    @Override
    public InodeStorageInformation getStorageInfo(FsInode inode) throws ChimeraFsException {

        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        InodeStorageInformation storageInfo = null;

        try {

            dbConnection.setAutoCommit(true);

            storageInfo = _sqlDriver.getStorageInfo(dbConnection, inode);

        } catch (SQLException e) {
            _log.error("setSorageInfo", e);
            throw new IOHimeraFsException(e.getMessage());
        } finally {
            tryToClose(dbConnection);
        }

        return storageInfo;

    }

    @Override
    public AccessLatency getAccessLatency(FsInode inode) throws ChimeraFsException {

        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        AccessLatency accessLatency = null;

        try {

            // read only
            dbConnection.setAutoCommit(true);

            accessLatency = _sqlDriver.getAccessLatency(dbConnection, inode);

        } catch (SQLException e) {
            _log.error("setSorageInfo", e);
            throw new IOHimeraFsException(e.getMessage());
        } finally {
            tryToClose(dbConnection);
        }

        return accessLatency;

    }

    @Override
    public RetentionPolicy getRetentionPolicy(FsInode inode) throws ChimeraFsException {

        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        RetentionPolicy retentionPolicy = null;

        try {

            // read only
            dbConnection.setAutoCommit(true);

            retentionPolicy = _sqlDriver.getRetentionPolicy(dbConnection, inode);

        } catch (SQLException e) {
            _log.error("setSorageInfo", e);
            throw new IOHimeraFsException(e.getMessage());
        } finally {
            tryToClose(dbConnection);
        }

        return retentionPolicy;

    }

    /*
     * inode checksum handling
     */
    @Override
    public void setInodeChecksum(FsInode inode, int type, String checksum) throws ChimeraFsException {

        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }


        try {
            dbConnection.setAutoCommit(false);

            _sqlDriver.setInodeChecksum(dbConnection, inode, type, checksum);

            dbConnection.commit();

        } catch (SQLException e) {
            tryToRollback(dbConnection);

            if (_sqlDriver.isForeignKeyError(e)) {
                throw new FileNotFoundHimeraFsException();
            }

            if (!_sqlDriver.isDuplicatedKeyError(e)) {
                _log.error("setInodeChecksum:", e);
                throw new IOHimeraFsException(e.getMessage());
            }
        } finally {
            tryToClose(dbConnection);
        }

    }

    @Override
    public void removeInodeChecksum(FsInode inode, int type) throws ChimeraFsException {
        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        try {
            dbConnection.setAutoCommit(true);

            _sqlDriver.removeInodeChecksum(dbConnection, inode, type);

        } catch (SQLException e) {
            _log.error("removeInodeChecksum", e);
            throw new IOHimeraFsException(e.getMessage());
        } finally {
            tryToClose(dbConnection);
        }
    }

    @Override
    public Set<Checksum> getInodeChecksums(FsInode inode) throws ChimeraFsException {
        Set<Checksum> checkSums = new HashSet<>();
        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }
        try {
            dbConnection.setAutoCommit(true);
            _sqlDriver.getInodeChecksums(dbConnection, inode, checkSums);
        } catch (SQLException e) {
            _log.error("getInodeChecksum", e);
            throw new IOHimeraFsException(e.getMessage());
        } finally {
            tryToClose(dbConnection);
        }
        return checkSums;
    }

    /**
     * Get inode's Access Control List. An empty list is returned if there are no ACL assigned
     * to the <code>inode</code>.
     * @param inode
     * @return acl
     */
    @Override
    public List<ACE> getACL(FsInode inode) throws ChimeraFsException {
        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        List<ACE> acl;
        try {
            dbConnection.setAutoCommit(true);

            acl = _sqlDriver.getACL(dbConnection, inode);

        } catch (SQLException e) {
            _log.error("Failed go getACL:", e);
            throw new IOHimeraFsException(e.getMessage());
        } finally {
            tryToClose(dbConnection);
        }
        return acl;
    }

    /**
     * Set inode's Access Control List. The existing ACL will be replaced.
     * @param dbConnection
     * @param inode
     * @param acl
     */
    @Override
    public void setACL(FsInode inode, List<ACE> acl) throws ChimeraFsException {
        Connection dbConnection;
        try {
            // get from pool
            dbConnection = _dbConnectionsPool.getConnection();
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }

        try {
            dbConnection.setAutoCommit(false);

            if (_sqlDriver.setACL(dbConnection, inode, acl)) {
                // empty stat will update ctime
                _sqlDriver.setInodeAttributes(dbConnection, inode, 0, new Stat());
            }
            dbConnection.commit();

        } catch (SQLException e) {
            _log.error("Failed to set ACL: ", e);
            tryToRollback(dbConnection);
            throw new IOHimeraFsException(e.getMessage());
        } finally {
            tryToClose(dbConnection);
        }
    }

    private static void checkNameLength(String name) throws InvalidNameChimeraException {
        if (name.length() > MAX_NAME_LEN) {
            throw new InvalidNameChimeraException("Name too long");
        }
    }

    public FsStat getFsStat0() throws ChimeraFsException {
        FsStat fsStat = null;
        Connection dbConnection = null;
        try {
            dbConnection = _dbConnectionsPool.getConnection();
            fsStat = _sqlDriver.getFsStat(dbConnection);
        } catch (SQLException e) {
            _log.error("Failed to obtain FsStat: {}", e.getMessage());
        } finally {
            tryToClose(dbConnection);
        }
        return fsStat;
    }

    @Override
    public FsStat getFsStat() throws ChimeraFsException {
        try {
            return _fsStatCache.get(DUMMY_KEY);
        }catch(ExecutionException e) {
            Throwable t = e.getCause();
            Throwables.propagateIfPossible(t, ChimeraFsException.class);
            throw new ChimeraFsException(t.getMessage(), t);
        }
    }

    ///////////////////////////////////////////////////////////////
    //
    //  Some information
    //
    @Override
    public String getInfo() {

        String databaseProductName = "Unknown";
        String databaseProductVersion = "Unknown";
        Connection dbConnection = null;
        try {
            dbConnection = _dbConnectionsPool.getConnection();
            if (dbConnection != null) {
                databaseProductName = dbConnection.getMetaData().getDatabaseProductName();
                databaseProductVersion = dbConnection.getMetaData().getDatabaseProductVersion();
            }
        } catch (SQLException se) {
            // ignored
        } finally {
            tryToClose(dbConnection);
        }

        StringBuilder sb = new StringBuilder();

        sb.append("DB        : ").append(_dbConnectionsPool).append("\n");
        sb.append("DB Engine : ").append(databaseProductName).append(" ").append(databaseProductVersion).append("\n");
        sb.append("rootID    : ").append(_rootInode).append("\n");
        sb.append("wormID    : ").append(_wormID).append("\n");
        sb.append("FsId      : ").append(_fsId).append("\n");
        return sb.toString();
    }


    /*
     * (non-Javadoc)
     * @see java.io.Closeable#close()
     */
    @Override
    public void close() throws IOException {
	// enforced by the interface
    }

    private final static byte[] FH_V0_BIN = new byte[] {0x30, 0x30, 0x30, 0x30};
    private final static byte[] FH_V0_REG = new byte[]{0x30, 0x3a};
    private final static byte[] FH_V0_PFS = new byte[]{0x32, 0x35, 0x35, 0x3a};

    private static boolean arrayStartsWith(byte[] a1, byte[] a2) {
        if (a1.length < a2.length) {
            return false;
        }
        for (int i = 0; i < a2.length; i++) {
            if (a1[i] != a2[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public FsInode inodeFromBytes(byte[] handle) throws ChimeraFsException {

        if (arrayStartsWith(handle, FH_V0_REG) || arrayStartsWith(handle, FH_V0_PFS)) {
            return inodeFromBytesOld(handle);
        } else if (arrayStartsWith(handle, FH_V0_BIN)) {
            return inodeFromBytesNew(InodeId.hexStringToByteArray(new String(handle)));
        } else {
            return inodeFromBytesNew(handle);
        }
    }

    private final static char[] HEX = new char[]{
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
    };

    /**
     * Returns a hexadecimal representation of given byte array.
     *
     * @param bytes whose string representation to return
     * @return a string representation of <tt>bytes</tt>
     */
    public static String toHexString(byte[] bytes) {

        char[] chars = new char[bytes.length * 2];
        int p = 0;
        for (byte b : bytes) {
            int i = b & 0xff;
            chars[p++] = HEX[i / 16];
            chars[p++] = HEX[i % 16];
        }
        return new String(chars);
    }

    private String[] getArgs(byte[] bytes) {

        StringTokenizer st = new StringTokenizer(new String(bytes), "[:]");
        int argc = st.countTokens();
        String[] args = new String[argc];
        for (int i = 0; i < argc; i++) {
            args[i] = st.nextToken();
        }

        return args;
    }

    FsInode inodeFromBytesNew(byte[] handle) throws ChimeraFsException {

        FsInode inode;

        if (handle.length < MIN_HANDLE_LEN) {
            throw new FileNotFoundHimeraFsException("File handle too short");
        }

        ByteBuffer b = ByteBuffer.wrap(handle);
        int fsid = b.get();
        int type = b.get();
        int idLen = b.get();
        byte[] id = new byte[idLen];
        b.get(id);
        int opaqueLen = b.get();
        if (opaqueLen > b.remaining()) {
            throw new FileNotFoundHimeraFsException("Bad Opaque len");
        }

        byte[] opaque = new byte[opaqueLen];
        b.get(opaque);

        FsInodeType inodeType = FsInodeType.valueOf(type);
        String inodeId = toHexString(id);

        switch (inodeType) {
            case INODE:
                int level = Integer.parseInt( new String(opaque));
                inode = new FsInode(this, inodeId, level);
                break;

            case ID:
                inode = new FsInode_ID(this, inodeId);
                break;

            case TAGS:
                inode = new FsInode_TAGS(this, inodeId);
                break;

            case TAG:
                String tag = new String(opaque);
                inode = new FsInode_TAG(this, inodeId, tag);
                break;

            case NAMEOF:
                inode = new FsInode_NAMEOF(this, inodeId);
                break;
            case PARENT:
                inode = new FsInode_PARENT(this, inodeId);
                break;

            case PATHOF:
                inode = new FsInode_PATHOF(this, inodeId);
                break;

            case CONST:
                inode = new FsInode_CONST(this, inodeId);
                break;

            case PSET:
                inode = new FsInode_PSET(this, inodeId, getArgs(opaque));
                break;

            case PCUR:
                inode = new FsInode_PCUR(this, inodeId);
                break;

            case PLOC:
                inode = new FsInode_PLOC(this, inodeId);
                break;

            case PCRC:
                inode = new FsInode_PCRC(this, inodeId);
                break;

            default:
                throw new FileNotFoundHimeraFsException("Unsupported file handle type: " + inodeType);
        }
        return inode;
    }

    FsInode inodeFromBytesOld(byte[] handle) throws ChimeraFsException {
        FsInode inode = null;

        String strHandle = new String(handle);

        StringTokenizer st = new StringTokenizer(strHandle, "[:]");

        if (st.countTokens() < 3) {
            throw new IllegalArgumentException("Invalid HimeraNFS handler.("
                    + strHandle + ")");
        }

        /*
         * reserved for future use
         */
        int fsId = Integer.parseInt(st.nextToken());

        String type = st.nextToken();

        try {
            // IllegalArgumentException will be thrown is it's wrong type

            FsInodeType inodeType = FsInodeType.valueOf(type);
            String id;
            int argc;
            String[] args;

            switch (inodeType) {
                case INODE:
                    id = st.nextToken();
                    int level = 0;
                    if (st.countTokens() > 0) {
                        level = Integer.parseInt(st.nextToken());
                    }
                    inode = new FsInode(this, id, level);
                    break;

                case ID:
                    id = st.nextToken();
                    inode = new FsInode_ID(this, id);
                    break;

                case TAGS:
                    id = st.nextToken();
                    inode = new FsInode_TAGS(this, id);
                    break;

                case TAG:
                    id = st.nextToken();
                    String tag = st.nextToken();
                    inode = new FsInode_TAG(this, id, tag);
                    break;

                case NAMEOF:
                    id = st.nextToken();
                    inode = new FsInode_NAMEOF(this, id);
                    break;
                case PARENT:
                    id = st.nextToken();
                    inode = new FsInode_PARENT(this, id);
                    break;

                case PATHOF:
                    id = st.nextToken();
                    inode = new FsInode_PATHOF(this, id);
                    break;

                case CONST:
                    String cnst = st.nextToken();
                    inode = new FsInode_CONST(this, cnst);
                    break;

                case PSET:
                    id = st.nextToken();
                    argc = st.countTokens();
                    args = new String[argc];
                    for (int i = 0; i < argc; i++) {
                        args[i] = st.nextToken();
                    }
                    inode = new FsInode_PSET(this, id, args);
                    break;

                case PCUR:
                    id = st.nextToken();
                    inode = new FsInode_PCUR(this, id);
                    break;

                case PLOC:
                    id = st.nextToken();
                    inode = new FsInode_PLOC(this, id);
                    break;

                case PCRC:
                    id = st.nextToken();
                    inode = new FsInode_PCRC(this, id);
                    break;

            }
        } catch (IllegalArgumentException iae) {
            _log.info("Failed to generate an inode from file handle : {} : {}", strHandle, iae);
            inode = null;
        }

        return inode;
    }

    @Override
    public byte[] inodeToBytes(FsInode inode) throws ChimeraFsException {
        return inode.getIdentifier();
    }

    @Override
    public String getFileLocality(FsInode_PLOC node) throws ChimeraFsException {
        throw new ChimeraFsException(NOT_IMPL);
    }

    /**
     * To maintain the abstraction level, we relegate the actual
     * callout to the subclass.
     */
    @Override
    public void pin(String pnfsid, long lifetime) throws ChimeraFsException {
       throw new ChimeraFsException(NOT_IMPL);
    }

   /**
    * To maintain the abstraction level, we relegate the actual
    * callout to the subclass.
    */
    @Override
    public void unpin(String pnfsid) throws ChimeraFsException {
       throw new ChimeraFsException(NOT_IMPL);
    }

    private static class RootInode extends FsInode
    {
        public RootInode(FileSystemProvider fs)
        {
            super(fs, "000000000000000000000000000000000000");
        }

        @Override
        public boolean exists() throws ChimeraFsException
        {
            return true;
        }

        @Override
        public boolean isDirectory()
        {
            return true;
        }

        @Override
        public boolean isLink()
        {
            return false;
        }

        @Override
        public FsInode getParent()
        {
            return null;
        }
    }
}
