# Useful utility functions for shell programming

# Returns true if $1 is contained as a word in $2.
contains() # in $1 = word, in $2+ = list
{
    local word
    word=$1
    shift
    for i in "$@"; do
	if [ "$word" = "$i" ]; then
	    return 0
	fi
    done
    return 1
}

# Reverses a list of words
reverse() # out $1 = reverse list, in $2+ = space delimited list of words,
{
    local out
    local ret
    out=$1
    shift
    for s in "$@"; do
        ret="${s} ${ret}"
    done
    eval $out=\"$ret\"
}

# Simplified cross platform version of the BSD column utility. Formats
# stdin into columns.
column()
{
    awk -F "\t" '{ lines[NR] = $0; for (i = 1; i <= NF; i++) widths[i]=max(widths[i], length($i)) }; END { for (i = 1; i <= NR; i++) { $0 = lines[i]; for (j = 1; j <= NF; j++) printf("%-" widths[j] "s ", $j); print ""; } } function max(i, j) { if (i < j) return j; else return i; }'
}

# Utility function for printing to stdout with a line width
# maximum of 75 characters. Longer lines are broken into several
# lines. Each argument is interpreted as a separate paragraph.
printp() # in $1+ = list of paragraphs
{
    local line
    local line2

    while [ $# -gt 0 ]; do
	# If line is non empty, then we need to print a
	# paragraph separator.
	if [ -n "$line" ]; then
	    echo
	fi
	line=
	for word in $1; do
	    line2="$line $word"
	    if [ ${#line2} -gt 75 ]; then
		echo $line
		line=$word
	    else
		line=$line2
	    fi
	done
	echo $line
	shift
    done
}

# Prints an error message to stderr and exist with status $1
fail() # in $1 = exit status, in $2- = list of paragraphs, see printp
{
    local n
    n=$1
    shift
    printp "$@"
    exit $n
} 1>&2

# Returns 0 if the given file is empty, 1 otherwise. The file must
# exist.
isFileEmpty() # in $1 = file
{
    [ $(wc -l < $1) -eq 0 ]
}

# Returns whether a process with a given PID is running
isRunning()# in $1 = pid
{
    rc=0
    ps -p "$1" 1>/dev/null 2>/dev/null || rc=1
    return $rc
}


# Searches for executables and exists with an error message if any of
# them are not found on the PATH.
require() # in $1+ = executable
{
    local tool
    for tool in "$@"; do
	if ! type "${tool}" > /dev/null 2>&1; then
	    fail 1 "Could not find ${tool}. ${tool} is a required tool."
	fi
    done
}

# Tries to locate a Java tool. The name of the tool is provided as an
# argument to the function. Sets the variable of the same name to the
# path to that tool unless the variable was already initialized and
# pointed to the location of the tool.
#
# For instance calling 'requireJavaTool jmap' will set the variable
# jmap to the full path of the jmap utility, unless the variable jmap
# already contained the path to the jmap utility.
#
# Returns with a non-zero exit code if the tool could not be found.
findJavaTool() # in $1 = tool
{
    eval local path=\$$1

    # Check for cached or predefined result
    if [ -n "$path" ]; then
        if [ ! -x "$path" ]; then
            return 0
        fi
    fi

    # See if JAVA is predefined
    if [ -n "$JAVA" ]; then
        path="$(dirname ${JAVA})/$1"
        if [ -x "$path" ]; then
            eval $1=\"$path\"
            return 0
        fi
    fi

    # Look in JAVA_HOME
    if [ -n "$JAVA_HOME" ]; then
        path="$JAVA_HOME/bin/$1"
        if [ -x "$path" ]; then
            eval $1=\"$path\"
            return 0
        fi
    fi

    # Check if it is on the path
    path="$(which $1)"
    if [ -x "$path" ]; then
        eval $1=\"$path\"
        return 0
    fi

    return 1
}

# Converts a string describing some amount of disk space (using an
# optional suffix of k, K, m, M, g, G, t, T, for powers of 1024) to an
# integer number of GiB.
stringToGiB() # in $1 = size, out $2 = size in GiB
{
    local gib
    case $1 in
        *k)
            gib=$((${1%?}/(1024*1024)))
            ;;

        *K)
            gib=$((${1%?}/(1024*1024)))
            ;;

        *m)
            gib=$((${1%?}/1024))
            ;;

        *M)
            gib=$((${1%?}/1024))
            ;;

        *g)
            gib=$((${1%?}))
            ;;

        *G)
            gib=$((${1%?}))
            ;;

        *t)
            gib=$((${1%?}*1024))
            ;;

        *T)
            gib=$((${1%?}*1024))
            ;;

        *)
            gib=$(($1/(1024*1024*1024)))
            ;;
    esac
    eval $2=\"$gib\"
}

# Extracts the amount of free space in GiB.
getFreeSpace() # in $1 = path
{
    [ -d "$1" ] && ( df -k "${1}" | awk 'NR == 2 { if (NF < 4) { getline; x = $3 } else { x = $4 }; printf "%d", x / (1024 * 1024)}' )
}

# Reads configuration file into shell variables. The shell variable
# names can optionally be prefixed. Returns 1 if file does not exist.
readconf() # in $1 = file in $2 = prefix
{
    [ -f "$1" ] &&
    eval $(sed -f "${DCACHE_LIB}/config.sed" "$1"  |
        sed -e "s/\([^=]*\)=\(.*\)/$2\1=\2/")
}

matchesAny() # $1 = word, $2+ = patterns
{
    local word
    word="$1"
    shift
    while [ $# -gt 0 ]; do
        if [ -z "${word##$1}" ]; then
            return 0
        fi
        shift
    done
    return 1
}

# Check prerequisites
require awk df sed wc dirname which
