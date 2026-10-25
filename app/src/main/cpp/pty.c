/*
 * Minimal pseudo-terminal helper for ASL.
 *
 * Android's Java APIs cannot allocate a PTY, so we do it natively: open /dev/ptmx, fork, make the slave the
 * child's controlling terminal, and exec. Same technique as other Android terminal apps.
 *
 * The pty_* functions are plain POSIX and are tested on a normal Linux host (see PTY_HOST_TEST below);
 * the JNI glue at the bottom is only compiled for Android.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

/* Returns the master fd (>= 0) and stores the child pid, or -errno. */
int pty_spawn(const char *cmd, const char *cwd, char *const argv[], char *const envp[],
              int rows, int cols, int *pid_out) {
    int master = open("/dev/ptmx", O_RDWR | O_CLOEXEC | O_NOCTTY);
    if (master < 0) return -errno;
    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        int e = errno;
        close(master);
        return -e;
    }
    char slave_name[64];
    if (ptsname_r(master, slave_name, sizeof(slave_name)) != 0) {
        int e = errno;
        close(master);
        return -e;
    }

    struct termios tios;
    if (tcgetattr(master, &tios) == 0) {
        tios.c_iflag |= IUTF8;       /* correct backspace handling for UTF-8 */
        tios.c_iflag &= ~(IXON | IXOFF);
        tcsetattr(master, TCSANOW, &tios);
    }
    struct winsize ws = {(unsigned short) rows, (unsigned short) cols, 0, 0};
    ioctl(master, TIOCSWINSZ, &ws);

    pid_t pid = fork();
    if (pid < 0) {
        int e = errno;
        close(master);
        return -e;
    }
    if (pid == 0) {
        /* child: only async-signal-safe calls from here on */
        setsid();
        int slave = open(slave_name, O_RDWR);
        if (slave < 0) _exit(126);
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, 0);
        dup2(slave, 1);
        dup2(slave, 2);
        for (int fd = 3; fd < 1024; fd++) close(fd);
        if (cwd != NULL && chdir(cwd) != 0) _exit(125);
        execve(cmd, argv, envp);
        const char msg[] = "exec failed\n";
        if (write(2, msg, sizeof(msg) - 1) < 0) { /* ignore */ }
        _exit(127);
    }
    *pid_out = (int) pid;
    return master;
}

int pty_set_size(int fd, int rows, int cols) {
    struct winsize ws = {(unsigned short) rows, (unsigned short) cols, 0, 0};
    return ioctl(fd, TIOCSWINSZ, &ws) == 0 ? 0 : -errno;
}

/* Blocks until the child exits. Returns its exit status, or -signal if it was killed by a signal. */
int pty_wait(int pid) {
    int status = 0;
    for (;;) {
        pid_t r = waitpid((pid_t) pid, &status, 0);
        if (r < 0) {
            if (errno == EINTR) continue;
            return -ECHILD;
        }
        break;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return -1;
}

/* Signals the whole process group of the child (it is a session leader), falling back to the pid. */
int pty_signal(int pid, int sig) {
    if (kill(-(pid_t) pid, sig) == 0) return 0;
    return kill((pid_t) pid, sig) == 0 ? 0 : -errno;
}

#ifndef PTY_HOST_TEST
#include <jni.h>

static char **to_c_array(JNIEnv *env, jobjectArray arr, int *count_out) {
    jsize n = (*env)->GetArrayLength(env, arr);
    char **out = (char **) calloc((size_t) n + 1, sizeof(char *));
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        const char *utf = (*env)->GetStringUTFChars(env, s, NULL);
        out[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, s, utf);
        (*env)->DeleteLocalRef(env, s);
    }
    out[n] = NULL;
    if (count_out) *count_out = (int) n;
    return out;
}

static void free_c_array(char **a) {
    if (!a) return;
    for (char **p = a; *p; p++) free(*p);
    free(a);
}

JNIEXPORT jint JNICALL
Java_dev_asl_app_terminal_Pty_spawn(JNIEnv *env, jclass clazz, jstring jcmd, jstring jcwd,
                                    jobjectArray jargv, jobjectArray jenvp, jint rows, jint cols,
                                    jintArray jpid) {
    (void) clazz;
    const char *cmd = (*env)->GetStringUTFChars(env, jcmd, NULL);
    const char *cwd = (*env)->GetStringUTFChars(env, jcwd, NULL);
    char **argv = to_c_array(env, jargv, NULL);
    char **envp = to_c_array(env, jenvp, NULL);
    int pid = 0;
    int fd = pty_spawn(cmd, cwd, argv, envp, rows, cols, &pid);
    (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
    (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
    free_c_array(argv);
    free_c_array(envp);
    if (fd >= 0) {
        jint p = pid;
        (*env)->SetIntArrayRegion(env, jpid, 0, 1, &p);
    }
    return fd;
}

JNIEXPORT jint JNICALL
Java_dev_asl_app_terminal_Pty_setSize(JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols) {
    (void) env; (void) clazz;
    return pty_set_size(fd, rows, cols);
}

JNIEXPORT jint JNICALL
Java_dev_asl_app_terminal_Pty_waitFor(JNIEnv *env, jclass clazz, jint pid) {
    (void) env; (void) clazz;
    return pty_wait(pid);
}

JNIEXPORT jint JNICALL
Java_dev_asl_app_terminal_Pty_signal(JNIEnv *env, jclass clazz, jint pid, jint sig) {
    (void) env; (void) clazz;
    return pty_signal(pid, sig);
}
#endif /* PTY_HOST_TEST */
