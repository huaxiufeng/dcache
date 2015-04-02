package dmg.cells.nucleus;

import com.google.common.base.Throwables;
import com.google.common.collect.Queues;
import com.google.common.util.concurrent.SettableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import javax.annotation.Nonnull;

import java.io.FileNotFoundException;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import dmg.util.Pinboard;
import dmg.util.logback.FilterThresholds;
import dmg.util.logback.RootFilterThresholds;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Iterables.consumingIterable;
import static com.google.common.util.concurrent.MoreExecutors.sameThreadExecutor;

/**
 *
 *
 * @author Patrick Fuhrmann
 * @version 0.1, 15 Feb 1998
 */
public class CellNucleus implements ThreadFactory
{
    private final static Logger LOGGER =
            LoggerFactory.getLogger(CellNucleus.class);

    private static final int PINBOARD_DEFAULT_SIZE = 200;
    private static final  int    INITIAL  =  0;
    private static final  int    ACTIVE   =  1;
    private static final  int    REMOVING =  2;
    private static final  int    DEAD     =  3;
    private static CellGlue __cellGlue;
    private final  String    _cellName;
    private final  String    _cellType;
    private final  ThreadGroup _threads;
    private final  AtomicInteger _threadCounter = new AtomicInteger();
    private final  Cell      _cell;
    private final  Date      _creationTime   = new Date();

    private final AtomicInteger _state = new AtomicInteger(INITIAL);

    //  have to be synchronized map
    private final  Map<UOID, CellLock> _waitHash = new HashMap<>();
    private String _cellClass;

    private volatile ExecutorService _messageExecutor;
    private final AtomicInteger _eventQueueSize = new AtomicInteger();

    /**
     * Timer for periodic low-priority maintenance tasks. Shared among
     * all cell instances. Since a Timer is single-threaded,
     * it is important that the timer is not used for long-running or
     * blocking tasks, nor for time critical tasks.
     */
    private static final Timer _timer = new Timer("Cell maintenance task timer", true);

    /**
     * Task for calling the Cell nucleus message timeout mechanism.
     */
    private TimerTask _timeoutTask;

    private boolean _isPrivateMessageExecutor = true;

    private Pinboard _pinboard;
    private FilterThresholds _loggingThresholds;
    private final Queue<Runnable> _deferredTasks = Queues.synchronizedQueue(new ArrayDeque<Runnable>());

    public CellNucleus(Cell cell, String name) {

        this(cell, name, "Generic");
    }

    public CellNucleus(Cell cell, String name, String type)
    {
        setPinboard(new Pinboard(PINBOARD_DEFAULT_SIZE));

        if (__cellGlue == null) {
            //
            // the cell gluon hasn't yet been created
            // (we insist in creating a SystemCell first.)
            //
            if (cell instanceof SystemCell) {
                __cellGlue = new CellGlue(name);
                _cellName    = "System";
                _cellType    = "System";
                __cellGlue.setSystemNucleus(this);
            } else {
                throw new
                    IllegalArgumentException("System must be first Cell");
            }

        } else {
            //
            // we don't accept more then one System.cells
            //
            if (cell instanceof SystemCell) {
                throw new
                    IllegalArgumentException("System already exists");
            } else {
                String cellName    = name.replace('@', '+');

                if ((cellName == null) ||
                   (cellName.equals(""))) {
                    cellName = "*";
                }
                if (cellName.charAt(cellName.length() - 1) == '*') {
                    if (cellName.length() == 1) {
                	cellName = "$-"+getUnique();
                    } else {
                	cellName = cellName.substring(0,cellName.length()-1)+
                            "-"+getUnique();
                    }
                }

                _cellName = cellName;
                _cellType    = type;

            }
        }

        _cell = cell;
        _cellClass = _cell.getClass().getName();

        /* Instantiate management component for log filtering.
         */
        CellNucleus parentNucleus =
                CellNucleus.getLogTargetForCell(MDC.get(CDC.MDC_CELL));
        FilterThresholds parentThresholds =
                (parentNucleus.isSystemNucleus() || parentNucleus == this)
                        ? RootFilterThresholds.getInstance()
                        : parentNucleus.getLoggingThresholds();
        setLoggingThresholds(new FilterThresholds(parentThresholds));

        _threads = new ThreadGroup(__cellGlue.getMasterThreadGroup(), _cellName + "-threads");

        _messageExecutor =
                new ThreadPoolExecutor(1, 1,
                        0L, TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<Runnable>(),
                        this);

        LOGGER.info("Created {}", name);
    }

    /**
     * Start the timeout task.
     *
     * Cells rely on periodic calls to executeMaintenanceTasks to implement
     * message timeouts. This method starts a task which calls
     * executeMaintenanceTasks every 20 seconds.
     */
    private void startTimeoutTask()
    {
        if (_timeoutTask != null) {
            throw new IllegalStateException("Timeout task is already running");
        }
        _timeoutTask = new TimerTask() {
            @Override
            public void run()
            {
                try (CDC ignored = CDC.reset(CellNucleus.this)) {
                    try {
                        executeMaintenanceTasks();
                    } catch (Throwable e) {
                        Thread t = Thread.currentThread();
                        t.getUncaughtExceptionHandler().uncaughtException(t, e);
                    }
                }
            }
        };
        _timer.schedule(_timeoutTask, 20000, 20000);
    }

    /**
     * Returns the CellNucleus to which log messages tagged with a
     * given cell are associated.
     */
    public static CellNucleus getLogTargetForCell(String cell)
    {
        CellNucleus nucleus = null;
        if (__cellGlue != null) {
            if (cell != null) {
                nucleus = __cellGlue.getCell(cell);
            }
            if (nucleus == null) {
                nucleus = __cellGlue.getSystemNucleus();
            }
        }
        return nucleus;
    }

    void setSystemNucleus(CellNucleus nucleus) {
        __cellGlue.setSystemNucleus(nucleus);
    }

    boolean isSystemNucleus() {
        return this == __cellGlue.getSystemNucleus();
    }

    public String getCellName() { return _cellName; }
    public String getCellType() { return _cellType; }

    public String getCellClass()
    {
        return _cellClass;
    }

    public void setCellClass(String cellClass)
    {
        _cellClass = cellClass;
    }

    public CellAddressCore getThisAddress() {
        return new CellAddressCore(_cellName, __cellGlue.getCellDomainName());
    }

    public String getCellDomainName() {
        return __cellGlue.getCellDomainName();
    }
    public List<String> getCellNames() { return __cellGlue.getCellNames(); }
    public CellInfo getCellInfo(String name) {
        return __cellGlue.getCellInfo(name);
    }
    public CellInfo getCellInfo() {
        return __cellGlue.getCellInfo(getCellName());
    }

    public Map<String, Object> getDomainContext()
    {
        return __cellGlue.getCellContext();
    }

    public Reader getDomainContextReader(String contextName)
        throws FileNotFoundException  {
        Object o = __cellGlue.getCellContext(contextName);
        if (o == null) {
            throw new
                    FileNotFoundException("Context not found : " + contextName);
        }
        return new StringReader(o.toString());
    }
    public void   setDomainContext(String contextName, Object context) {
        __cellGlue.getCellContext().put(contextName, context);
    }
    public Object getDomainContext(String str) {
        return __cellGlue.getCellContext(str);
    }
    public String [] [] getClassProviders() {
        return __cellGlue.getClassProviders();
    }
    public synchronized void setClassProvider(String selection, String provider) {
        __cellGlue.setClassProvider(selection, provider);
    }
    Cell getThisCell() { return _cell; }

    CellInfo _getCellInfo() {
        CellInfo info = new CellInfo();
        info.setCellName(getCellName());
        info.setDomainName(getCellDomainName());
        info.setCellType(getCellType());
        info.setCreationTime(_creationTime);
        try {
            info.setCellVersion(_cell.getCellVersion());
        } catch(Exception e) {}
        try {
            info.setPrivateInfo(_cell.getInfo());
        } catch(Exception e) {
            info.setPrivateInfo("Not yet/No more available\n");
        }
        try {
            info.setShortInfo(_cell.toString());
        } catch(Exception e) {
            info.setShortInfo("Not yet/No more available");
        }
        info.setCellClass(_cellClass);
        try {
            info.setEventQueueSize(getEventQueueSize());
            info.setState(_state.get());
            info.setThreadCount(_threads.activeCount());
        } catch(Exception e) {
            info.setEventQueueSize(0);
            info.setState(0);
            info.setThreadCount(0);
        }
        return info;
    }

    public void setLoggingThresholds(FilterThresholds thresholds)
    {
        _loggingThresholds = thresholds;
    }

    public FilterThresholds getLoggingThresholds()
    {
        return _loggingThresholds;
    }

    public synchronized void setPinboard(Pinboard pinboard)
    {
        _pinboard = pinboard;
    }

    public synchronized Pinboard getPinboard()
    {
        return _pinboard;
    }

    /**
     * Executor used for incoming message delivery.
     */
    public synchronized void setMessageExecutor(ExecutorService executor)
    {
        checkNotNull(executor);
        int state = _state.get();
        checkState(state != REMOVING && state != DEAD);

        if (_isPrivateMessageExecutor) {
            _messageExecutor.shutdown();
        }
        _messageExecutor = executor;
        _isPrivateMessageExecutor = false;
    }


    private synchronized void shutdownPrivateExecutors()
    {
        if (_isPrivateMessageExecutor) {
            _messageExecutor.shutdown();
        }
    }

    public void  sendMessage(CellMessage msg,
                             boolean locally,
                             boolean remotely)
        throws SerializationException,
               NoRouteToCellException
    {
        if (!msg.isStreamMode()) {
            msg.touch();
        }

        EventLogger.sendBegin(this, msg, "async");
        try {
            __cellGlue.sendMessage(this, msg, locally, remotely);
        } finally {
            EventLogger.sendEnd(msg);
        }
    }

    /**
     * Sends <code>envelope</code> and waits <code>timeout</code>
     * milliseconds for an answer to arrive.  The answer will bypass
     * the ordinary queuing mechanism and will be delivered before any
     * other asynchronous message.  The answer need to have the
     * getLastUOID set to the UOID of the message send with
     * sendAndWait. If the answer does not arrive withing the specified
     * time interval, the method returns <code>null</code> and the
     * answer will be handled as if it was an ordinary asynchronous
     * message.
     *
     * This method mostly exists for backwards compatibility. dCache code
     * should use CellStub or CellEndpoint.
     *
     * @param envelope the cell message to be sent.
     * @param timeout milliseconds to wait for an answer.
     * @return the answer or null if the timeout was reached.
     * @throws SerializationException if the payload object of this
     *         message is not serializable.
     * @throws NoRouteToCellException if the destination
     *         could not be reached.
     * @throws ExecutionException if an exception was returned.
     */
    public CellMessage sendAndWait(CellMessage envelope, long timeout)
            throws SerializationException, NoRouteToCellException, InterruptedException, ExecutionException
    {
        final SettableFuture<CellMessage> future = SettableFuture.create();
        sendMessage(envelope, true, true,
                    new CellMessageAnswerable()
                    {
                        @Override
                        public void answerArrived(CellMessage request, CellMessage answer)
                        {
                            future.set(answer);
                        }

                        @Override
                        public void exceptionArrived(CellMessage request, Exception exception)
                        {
                            future.setException(exception);
                        }

                        @Override
                        public void answerTimedOut(CellMessage request)
                        {
                            future.set(null);
                        }
                    }, sameThreadExecutor(), timeout);
        try {
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return null;
        } catch (ExecutionException e) {
            Throwables.propagateIfInstanceOf(e.getCause(), NoRouteToCellException.class);
            Throwables.propagateIfInstanceOf(e.getCause(), SerializationException.class);
            throw e;
        }
    }

    public Map<UOID,CellLock > getWaitQueue()
    {
        synchronized (_waitHash) {
            return new HashMap<>(_waitHash);
        }
    }

    private int executeMaintenanceTasks()
    {
        Collection<CellLock> expired = new ArrayList<>();
        long now = System.currentTimeMillis();
        int size;

        synchronized (_waitHash) {
            Iterator<CellLock> i = _waitHash.values().iterator();
            while (i.hasNext()) {
                CellLock lock =  i.next();
                if (lock.getTimeout() < now) {
                    expired.add(lock);
                    i.remove();
                }
            }
            size = _waitHash.size();
        }

        //
        // _waitHash can't be used here. Otherwise
        // we will end up in a deadlock (NO LOCKS WHILE CALLING CALLBACKS)
        //
        for (final CellLock lock: expired) {
            try (CDC ignored = lock.getCdc().restore()) {
                try {
                    lock.getExecutor().execute(new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            CellMessage envelope = lock.getMessage();
                            try {
                                lock.getCallback().answerTimedOut(envelope);
                                EventLogger.sendEnd(envelope);
                            } catch (RejectedExecutionException e) {
                                /* May happen when the callback itself tries to schedule the call
                                 * on an executor. Put the request back and let it time out.
                                 */
                                synchronized (_waitHash) {
                                    _waitHash.put(envelope.getUOID(), lock);
                                }
                                LOGGER.warn("Failed to invoke callback: {}", e.toString());
                            }
                        }
                    });
                } catch (RejectedExecutionException e) {
                    /* Put it back and deal with it later.
                     */
                    synchronized (_waitHash) {
                        _waitHash.put(lock.getMessage().getUOID(), lock);
                    }
                    LOGGER.warn("Failed to invoke callback: {}", e.toString());
                } catch (RuntimeException e) {
                    /* Don't let a problem in the callback prevent us from
                     * expiring all messages.
                     */
                    Thread t = Thread.currentThread();
                    t.getUncaughtExceptionHandler().uncaughtException(t, e);
                }
            }
        }

        // Execute delayed operations
        for (Runnable task : consumingIterable(_deferredTasks)) {
            task.run();
        }

        return size;
    }

    /**
     * Sends <code>msg</code>.
     *
     * The <code>callback</code> argument specifies an object which is informed
     * as soon as an has answer arrived or if the timeout has expired.
     *
     * The callback is run in the supplied executor. The executor may
     * execute the callback inline, but such an executor must only be
     * used if the callback is non-blocking, and the callback should
     * refrain from CPU heavy operations. Care should be taken that
     * the executor isn't blocked by tasks waiting for the callback;
     * such tasks could lead to a deadlock.
     *
     * @param msg the cell message to be sent.
     * @param local whether to attempt delivery to cells in the same domain
     * @param remote whether to attempt delivery to cells in other domains
     * @param callback specifies an object class which will be informed
     *                 as soon as the message arrives.
     * @param executor the executor to run the callback in
     * @param timeout  is the timeout in msec.
     * @exception SerializationException if the payload object of this
     *            message is not serializable.
     */
    public void sendMessage(final CellMessage msg,
                            boolean local,
                            boolean remote,
                            final CellMessageAnswerable callback,
                            Executor executor,
                            long timeout)
        throws SerializationException
    {
        if (!msg.isStreamMode()) {
            msg.touch();
        }

        msg.setTtl(timeout);

        final UOID uoid = msg.getUOID();
        final CellLock lock = new CellLock(msg, callback, executor, timeout);

        EventLogger.sendBegin(this, msg, "callback");
        synchronized (_waitHash) {
            _waitHash.put(uoid, lock);
        }
        try {
            __cellGlue.sendMessage(this, msg, local, remote);
        } catch (SerializationException e) {
            synchronized (_waitHash) {
                _waitHash.remove(uoid);
            }
            EventLogger.sendEnd(msg);
            throw e;
        } catch (final Exception e) {
            synchronized (_waitHash) {
                _waitHash.remove(uoid);
            }
            try {
                executor.execute(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        try {
                            callback.exceptionArrived(msg, e);
                            EventLogger.sendEnd(msg);
                        } catch (RejectedExecutionException e) {
                            /* May happen when the callback itself tries to schedule the call
                             * on an executor. Put the request back and let it time out.
                             */
                            synchronized (_waitHash) {
                                _waitHash.put(uoid, lock);
                            }
                            LOGGER.error("Failed to invoke callback: {}", e.toString());
                        }
                    }
                });
            } catch (RejectedExecutionException e1) {
                /* Put it back and let it time out.
                 */
                synchronized (_waitHash) {
                    _waitHash.put(uoid, lock);
                }
                LOGGER.error("Failed to invoke callback: {}", e1.toString());
            }
        }
    }
    public void addCellEventListener(CellEventListener listener) {
        __cellGlue.addCellEventListener(this, listener);

    }
    public void export() { __cellGlue.export(this);  }
    /**
     *
     * The kill method schedules the specified cell for deletion.
     * The actual remove operation will run in a different
     * thread. So on return of this method the cell may
     * or may not be alive.
     */
    public void kill() {   __cellGlue.kill(this);  }
    /**
     *
     * The kill method schedules this Cell for deletion.
     * The actual remove operation will run in a different
     * thread. So on return of this method the cell may
     * or may not be alive.
     */
    public void kill(String cellName) throws IllegalArgumentException {
        __cellGlue.kill(this, cellName);
    }


    /**
     * List the threads of some cell to stdout.  This is
     * indended for diagnostic information.
     */
    public void listThreadGroupOf(String cellName) {
        __cellGlue.threadGroupList(cellName);
    }

    /**
     * Print diagnostic information about currently running
     * threads at warn level.
     */
    public void  threadGroupList() {
        Thread[] threads = new Thread[_threads.activeCount()];
        _threads.enumerate(threads);
        for(Thread thread : threads) {
            LOGGER.warn("Thread: {} [{}{}{}] ({}) {}",
                    thread.getName(),
                    (thread.isAlive() ? "A" : "-"),
                    (thread.isDaemon() ? "D" : "-"),
                    (thread.isInterrupted() ? "I" : "-"),
                    thread.getPriority(),
                    thread.getState());
            for(StackTraceElement s : thread.getStackTrace()) {
                LOGGER.warn("    {}", s);
            }
        }
    }



    /**
     * Blocks until the given cell is dead.
     *
     * @throws InterruptedException if another thread interrupted the
     * current thread before or while the current thread was waiting
     * for a notification. The interrupted status of the current
     * thread is cleared when this exception is thrown.
     * @return True if the cell died, false in case of a timeout.
     */
    public boolean join(String cellName)
        throws InterruptedException
    {
        return __cellGlue.join(cellName, 0);
    }

    /**
     * Blocks until the given cell is dead.
     *
     * @param timeout the maximum time to wait in milliseconds.
     * @throws InterruptedException if another thread interrupted the
     * current thread before or while the current thread was waiting
     * for a notification. The interrupted status of the current
     * thread is cleared when this exception is thrown.
     * @return True if the cell died, false in case of a timeout.
     */
    public boolean join(String cellName, long timeout)
        throws InterruptedException
    {
        return __cellGlue.join(cellName, timeout);
    }

    /**
     * Returns the non-daemon threads of a thread group.
     */
    private Collection<Thread> getNonDaemonThreads(ThreadGroup group)
    {
        Thread[] threads = new Thread[group.activeCount()];
        int count = group.enumerate(threads);
        Collection<Thread> nonDaemonThreads = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Thread thread = threads[i];
            if (!thread.isDaemon()) {
                nonDaemonThreads.add(thread);
            }
        }
        return nonDaemonThreads;
    }

    /**
     * Waits for at most timeout milliseconds for the termination of a
     * set of threads.
     *
     * @return true if all threads terminated, false otherwise
     */
    private boolean joinThreads(Collection<Thread> threads, long timeout)
        throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + timeout;
        for (Thread thread: threads) {
            if (thread.isAlive()) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) {
                    return false;
                }
                thread.join(wait);
                if (thread.isAlive()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Interrupts a set of threads.
     */
    private void killThreads(Collection<Thread> threads)
    {
        for (Thread thread: threads) {
            if (thread.isAlive()) {
                LOGGER.debug("killerThread : interrupting {}", thread.getName());
                thread.interrupt();
            }
        }
    }

    private Runnable wrapLoggingContext(final Runnable runnable)
    {
        return new Runnable() {
            @Override
            public void run() {
                try (CDC ignored = CDC.reset(CellNucleus.this)) {
                    runnable.run();
                }
            }
        };
    }

    private <T> Callable<T> wrapLoggingContext(final Callable<T> callable)
    {
        return new Callable<T>() {
            @Override
            public T call() throws Exception {
                try (CDC ignored = CDC.reset(CellNucleus.this)) {
                    return callable.call();
                }
            }
        };
    }

    /**
     * Submits a task for execution on the message thread.
     */
    <T> Future<T> invokeOnMessageThread(Callable<T> task)
    {
        return _messageExecutor.submit(wrapLoggingContext(task));
    }

    void invokeLater(Runnable runnable)
    {
        _deferredTasks.add(runnable);
    }

    @Override @Nonnull
    public Thread newThread(@Nonnull Runnable target)
    {
        return newThread(target, getCellName() + "-" + _threadCounter.getAndIncrement());
    }

    @Nonnull
    public Thread newThread(@Nonnull Runnable target, @Nonnull String name)
    {
        return new Thread(_threads, wrapLoggingContext(target), name);
    }

    //
    //  package
    //
    Thread [] getThreads(String cellName) {
        return __cellGlue.getThreads(cellName);
    }
    public ThreadGroup getThreadGroup() { return _threads; }
    Thread [] getThreads() {
        if (_threads == null) {
            return new Thread[0];
        }

        int threadCount = _threads.activeCount();
        Thread [] list  = new Thread[threadCount];
        int rc = _threads.enumerate(list);
        if (rc == list.length) {
            return list;
        }
        Thread [] ret = new Thread[rc];
        System.arraycopy(list, 0, ret, 0, rc);
        return ret;
    }

    int  getUnique() { return __cellGlue.getUnique(); }

    int getEventQueueSize()
    {
        return _eventQueueSize.get();
    }

    void addToEventQueue(MessageEvent ce) {
        //
        //
        if (ce instanceof RoutedMessageEvent) {
            if (_cell instanceof CellTunnel) {
                //
                // nothing to do (no transformation needed)
                //
            } else {
                //
                // originally this case has not been forseen,
                // but it appeared rather useful. It allows alias
                // cells which serves several different cells names.
                // mainly useful for debuggin purposes (see alias
                // package.
                //
                ce = new MessageEvent(ce.getMessage());
            }
        }

        CellMessage msg = ce.getMessage();
        LOGGER.trace("addToEventQueue : message arrived : {}", msg);

        CellLock lock;
        synchronized (_waitHash) {
            lock = _waitHash.remove(msg.getLastUOID());
        }

        if (lock != null) {
            //
            // we were waiting for you (sync or async)
            //
            LOGGER.trace("addToEventQueue : lock found for : {}", msg);
            try {
                _eventQueueSize.incrementAndGet();
                lock.getExecutor().execute(new CallbackTask(lock, msg));
            } catch (RejectedExecutionException e) {
                _eventQueueSize.decrementAndGet();
                /* Put it back; the timeout handler will eventually take care of it.
                 */
                synchronized (_waitHash) {
                    _waitHash.put(msg.getLastUOID(), lock);
                }
                LOGGER.error("Message queue overflow. Dropping {}", ce);
            }
        } else {
            try {
                EventLogger.queueBegin(ce);
                _eventQueueSize.incrementAndGet();
                _messageExecutor.execute(new DeliverMessageTask(ce));
            } catch (RejectedExecutionException e) {
                EventLogger.queueEnd(ce);
                _eventQueueSize.decrementAndGet();
                LOGGER.error("Message queue overflow. Dropping {}", ce);
            }
        }
    }

    public void start()
    {
        checkState(_state.compareAndSet(INITIAL, ACTIVE));

        //
        // make ourself known to the world
        //
        __cellGlue.addCell(_cellName, this);
        startTimeoutTask();
    }

    void shutdown(KillEvent event)
    {
        LOGGER.trace("Received {}", event);

        try (CDC ignored = CDC.reset(CellNucleus.this)) {
            checkState(_state.compareAndSet(INITIAL, REMOVING) || _state.compareAndSet(ACTIVE, REMOVING));
            addToEventQueue(LAST_MESSAGE_EVENT);
            try {
                _cell.prepareRemoval(event);
            } catch (Throwable e) {
                Thread t = Thread.currentThread();
                t.getUncaughtExceptionHandler().uncaughtException(t, e);
            }

            shutdownPrivateExecutors();

            LOGGER.debug("Waiting for all threads in {} to finish", _threads);

            try {
                Collection<Thread> threads = getNonDaemonThreads(_threads);

                /* Some threads shut down asynchronously. Give them
                 * one second before we start to kill them.
                 */
                while (!joinThreads(threads, 1000)) {
                    killThreads(threads);
                }
                _threads.destroy();
            } catch (IllegalThreadStateException e) {
                _threads.setDaemon(true);
            } catch (InterruptedException e) {
                LOGGER.warn("Interrupted while waiting for threads");
            }

            if (_timeoutTask != null) {
                _timeoutTask.cancel();
            }

            __cellGlue.destroy(CellNucleus.this);
            _state.set(DEAD);
        }
    }

    ////////////////////////////////////////////////////////////
    //
    //   create new cell by different arguments
    //   String, String [], Socket
    //   can choose between systemLoader only or
    //   Domain loader.
    //
    public Cell createNewCell(String cellClass,
                              String cellName,
                              String cellArgs,
                              boolean systemOnly)
        throws ClassNotFoundException,
               NoSuchMethodException,
               SecurityException,
               InstantiationException,
               InvocationTargetException,
               IllegalAccessException,
               ClassCastException
    {
        try {
            Object [] args = new Object[1];
            args[0] = cellArgs;
            return __cellGlue._newInstance(
                    cellClass, cellName, args, systemOnly);
        } catch (InvocationTargetException e) {
            Throwable t = e.getTargetException();
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            }
            if (t instanceof Error) {
                throw (Error) t;
            }
            throw e;
        }
    }

    public Class<?> loadClass(String className) throws ClassNotFoundException
    {
        return __cellGlue.loadClass(className);
    }

    public Cell  createNewCell(String className,
                                 String cellName,
                                 String [] argsClassNames,
                                 Object [] args)
        throws ClassNotFoundException,
               NoSuchMethodException,
               InstantiationException,
               IllegalAccessException,
               InvocationTargetException,
               ClassCastException
    {
        if (argsClassNames == null) {
            return __cellGlue._newInstance(
                    className, cellName, args, false);
        } else {
            return __cellGlue._newInstance(
                    className, cellName, argsClassNames, args, false);
        }
    }

    public Cell createNewCell(String cellClass,
                              String cellName,
                              Socket socket,
                              boolean systemOnly)
        throws ClassNotFoundException,
               NoSuchMethodException,
               SecurityException,
               InstantiationException,
               InvocationTargetException,
               IllegalAccessException,
               ClassCastException          {

        Object [] args = new Object[1];
        args[0] = socket;

        return __cellGlue._newInstance(cellClass,
                                       cellName,
                                       args,
                                       systemOnly);
    }
    ////////////////////////////////////////////////////////////
    //
    //
    // the routing stuff
    //
    public void routeAdd(CellRoute  route) throws IllegalArgumentException {
        __cellGlue.routeAdd(route);
    }
    public void routeDelete(CellRoute  route) throws IllegalArgumentException {
        __cellGlue.routeDelete(route);
    }
    CellRoute routeFind(CellAddressCore addr) {
        return __cellGlue.getRoutingTable().find(addr);
    }
    CellRoutingTable getRoutingTable() { return __cellGlue.getRoutingTable(); }
    CellRoute [] getRoutingList() { return __cellGlue.getRoutingList(); }
    //
    List<CellTunnelInfo> getCellTunnelInfos() { return __cellGlue.getCellTunnelInfos(); }
    //

    private static final MessageEvent LAST_MESSAGE_EVENT = new LastMessageEvent();

    private abstract class AbstractNucleusTask implements Runnable
    {
        protected abstract void innerRun();

        @Override
        public void run ()
        {
            try (CDC ignored = CDC.reset(CellNucleus.this)) {
                try {
                    innerRun();
                } catch (Throwable e) {
                    Thread t = Thread.currentThread();
                    t.getUncaughtExceptionHandler().uncaughtException(t, e);
                }
            }
        }
    }

    private class CallbackTask extends AbstractNucleusTask
    {
        private final CellLock _lock;
        private final CellMessage _message;

        public CallbackTask(CellLock lock, CellMessage message)
        {
            _lock = lock;
            _message = message;
        }

        @Override
        public void innerRun()
        {
            _eventQueueSize.decrementAndGet();
            try (CDC ignored = _lock.getCdc().restore()) {
                CellMessageAnswerable callback =
                        _lock.getCallback();

                CellMessage answer;
                Object obj;
                try {
                    answer = _message.decode();
                    obj = answer.getMessageObject();
                } catch (SerializationException e) {
                    LOGGER.warn(e.getMessage());
                    obj = e;
                    answer = null;
                }

                CellMessage request = _lock.getMessage();
                try {
                    if (obj instanceof Exception) {
                        callback.exceptionArrived(request, (Exception) obj);
                    } else {
                        callback.answerArrived(request, answer);
                    }
                    EventLogger.sendEnd(request);
                } catch (RejectedExecutionException e) {
                    /* May happen when the callback itself tries to schedule the call
                     * on an executor. Put the request back and let it time out.
                     */
                    synchronized (_waitHash) {
                        _waitHash.put(request.getUOID(), _lock);
                    }
                    LOGGER.error("Failed to invoke callback: {}", e.toString());
                }
                LOGGER.trace("addToEventQueue : callback done for : {}", _message);
            }
        }
    }

    private class DeliverMessageTask extends AbstractNucleusTask
    {
        private final CellEvent _event;

        public DeliverMessageTask(CellEvent event)
        {
            _event = event;
        }

        @Override
        public void innerRun()
        {
            EventLogger.queueEnd(_event);
            _eventQueueSize.decrementAndGet();

            if (_event instanceof LastMessageEvent) {
                LOGGER.trace("messageThread : LastMessageEvent arrived");
                _cell.messageArrived((MessageEvent) _event);
            } else if (_event instanceof RoutedMessageEvent) {
                LOGGER.trace("messageThread : RoutedMessageEvent arrived");
                _cell.messageArrived((RoutedMessageEvent) _event);
            } else if (_event instanceof MessageEvent) {
                MessageEvent msgEvent = (MessageEvent) _event;
                LOGGER.trace("messageThread : MessageEvent arrived");
                CellMessage msg;
                try {
                    msg = msgEvent.getMessage().decode();
                } catch (SerializationException e) {
                    CellMessage envelope = msgEvent.getMessage();
                    LOGGER.error(String
                            .format("Discarding a malformed message from %s with UOID %s and session [%s]: %s",
                                    envelope.getSourcePath(),
                                    envelope.getUOID(),
                                    envelope.getSession(),
                                    e.getMessage()), e);
                    return;
                }

                CDC.setMessageContext(msg);
                try {
                    LOGGER.trace("messageThread : delivering message: {}", msg);
                    _cell.messageArrived(new MessageEvent(msg));
                    LOGGER.trace("messageThread : delivering message done: {}", msg);
                } catch (RuntimeException e) {
                    if (!msg.isReply()) {
                        try {
                            msg.revertDirection();
                            msg.setMessageObject(e);
                            sendMessage(msg, true, true);
                        } catch (NoRouteToCellException f) {
                            LOGGER.error("PANIC : Problem returning answer: {}", f);
                        }
                    }
                    throw e;
                } finally {
                    CDC.clearMessageContext();
                }
            }
        }
    }
}
