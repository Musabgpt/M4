#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

/* PTY allocated in native code: the Android app needs neither ADB nor Termux. */
typedef struct { int master; pid_t child; } Pty;
static void raise_io(JNIEnv *env, const char *message) {
    char buf[256];
    snprintf(buf,sizeof(buf),"%s: %s",message,strerror(errno));
    jclass cls=(*env)->FindClass(env,"java/io/IOException");
    if(cls) (*env)->ThrowNew(env,cls,buf);
}
JNIEXPORT jlong JNICALL
Java_io_musab_arabicterminal_NativePty_start(JNIEnv* env,jclass type,jboolean root,
             jstring homeString,jstring tempString,jint rows,jint cols,
             jstring linuxRootString,jstring prootString,jstring loaderString) {
    (void)type;
    const char *home=(*env)->GetStringUTFChars(env,homeString,NULL);
    if(!home)return 0;
    const char *temp=(*env)->GetStringUTFChars(env,tempString,NULL);
    if(!temp){(*env)->ReleaseStringUTFChars(env,homeString,home);return 0;}
    const char *guest=NULL,*proot=NULL,*loader=NULL;
    if(linuxRootString!=NULL && prootString!=NULL && loaderString!=NULL) {
        guest=(*env)->GetStringUTFChars(env,linuxRootString,NULL);
        proot=(*env)->GetStringUTFChars(env,prootString,NULL);
        loader=(*env)->GetStringUTFChars(env,loaderString,NULL);
        if(!guest||!proot||!loader) {
            if(guest)(*env)->ReleaseStringUTFChars(env,linuxRootString,guest);
            if(proot)(*env)->ReleaseStringUTFChars(env,prootString,proot);
            if(loader)(*env)->ReleaseStringUTFChars(env,loaderString,loader);
            (*env)->ReleaseStringUTFChars(env,tempString,temp);
            (*env)->ReleaseStringUTFChars(env,homeString,home);
            return 0;
        }
    }
    int master=posix_openpt(O_RDWR|O_NOCTTY|O_CLOEXEC);
    if(master<0){raise_io(env,"تعذر فتح جهاز الطرفية");goto failure;}
    if(grantpt(master)!=0 || unlockpt(master)!=0){
        raise_io(env,"تعذر تهيئة PTY");goto failure;
    }
    char slaveName[128];
    if(ptsname_r(master,slaveName,sizeof(slaveName))!=0){
        raise_io(env,"تعذر إيجاد الطرفية الثانوية");goto failure;
    }
    pid_t pid=fork();
    if(pid<0){raise_io(env,"تعذر تشغيل العملية");goto failure;}
    if(pid==0){
        if(setsid()<0)_exit(126);
        int slave=open(slaveName,O_RDWR|O_NOCTTY);
        if(slave<0)_exit(126);
        if(ioctl(slave,TIOCSCTTY,0)<0)_exit(126);
        struct winsize sz={.ws_row=(unsigned short)(rows>0?rows:24),
                           .ws_col=(unsigned short)(cols>0?cols:80)};
        ioctl(slave,TIOCSWINSZ,&sz);
        if(dup2(slave,STDIN_FILENO)<0 || dup2(slave,STDOUT_FILENO)<0 ||
           dup2(slave,STDERR_FILENO)<0)_exit(126);
        if(slave>2)close(slave);
        close(master);
        setenv("HOME",home,1);
        setenv("TMPDIR",temp,1);
        setenv("PATH","/system/bin:/system/xbin:/vendor/bin",1);
        setenv("TERM","xterm-256color",1);
        setenv("LANG","C.UTF-8",1);
        setenv("PS1","عربي$ ",1);
        chdir(home);
        if(guest) {
            char *lastSlash=strrchr(proot,'/');
            if(lastSlash) {
                char libdir[1024];
                size_t n=(size_t)(lastSlash-proot);
                if(n<sizeof(libdir)) {
                    memcpy(libdir,proot,n);libdir[n]='\0';
                    setenv("LD_LIBRARY_PATH",libdir,1);
                }
            }
            setenv("PROOT_LOADER",loader,1);
            setenv("PROOT_NO_SECCOMP","1",1);
            setenv("PROOT_TMP_DIR",temp,1);
            setenv("HOME","/root",1);
            setenv("PATH","/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",1);
            execl(proot,"proot","-r",guest,"-0","-b","/dev","-b","/proc",
                  "-b","/sys","-w","/root","/bin/bash","-l",(char*)NULL);
            perror("Failed to start Alpine Linux under PRoot");
        } else if(root) {
            /* Root is optional; consent and access are decided by the device's su. */
            execlp("su","su","-c","exec /system/bin/sh -i",(char*)NULL);
            perror("تعذر تشغيل الجذر");
        } else {
            execl("/system/bin/sh","sh","-i",(char*)NULL);
            perror("تعذر تشغيل الصدفة");
        }
        _exit(127);
    }
    if(guest)(*env)->ReleaseStringUTFChars(env,linuxRootString,guest);
    if(proot)(*env)->ReleaseStringUTFChars(env,prootString,proot);
    if(loader)(*env)->ReleaseStringUTFChars(env,loaderString,loader);
    (*env)->ReleaseStringUTFChars(env,tempString,temp);
    (*env)->ReleaseStringUTFChars(env,homeString,home);
    Pty *pty=(Pty*)calloc(1,sizeof(Pty));
    if(!pty){
        kill(-pid,SIGHUP);
        close(master);
        errno=ENOMEM;
        raise_io(env,"تعذر تخصيص ذاكرة الطرفية");
        return 0;
    }
    pty->master=master;pty->child=pid;
    return (jlong)(intptr_t)pty;
failure:
    if(master>=0)close(master);
    if(guest)(*env)->ReleaseStringUTFChars(env,linuxRootString,guest);
    if(proot)(*env)->ReleaseStringUTFChars(env,prootString,proot);
    if(loader)(*env)->ReleaseStringUTFChars(env,loaderString,loader);
    (*env)->ReleaseStringUTFChars(env,tempString,temp);
    (*env)->ReleaseStringUTFChars(env,homeString,home);
    return 0;
}
JNIEXPORT jint JNICALL
Java_io_musab_arabicterminal_NativePty_read(JNIEnv *env,jclass type,jlong handle,jbyteArray result) {
    (void)type;
    Pty *pty=(Pty*)(intptr_t)handle;
    if(!pty||!result)return -1;
    jsize size=(*env)->GetArrayLength(env,result);
    if(size<=0)return 0;
    jbyte *buf=(*env)->GetByteArrayElements(env,result,NULL);
    if(!buf)return -1;
    ssize_t n;
    do{n=read(pty->master,buf,(size_t)size);}while(n<0&&errno==EINTR);
    (*env)->ReleaseByteArrayElements(env,result,buf,n>0?0:JNI_ABORT);
    return n>0?(jint)n:-1;
}
JNIEXPORT jint JNICALL
Java_io_musab_arabicterminal_NativePty_write(JNIEnv *env,jclass type,jlong handle,jbyteArray data,jint len) {
    (void)type;
    Pty *pty=(Pty*)(intptr_t)handle;
    if(!pty||!data||len<0||len>(*env)->GetArrayLength(env,data))return -1;
    jbyte *buf=(*env)->GetByteArrayElements(env,data,NULL);
    if(!buf)return -1;
    size_t count=0;
    while(count<(size_t)len){
        ssize_t n=write(pty->master,buf+count,(size_t)len-count);
        if(n<0&&errno==EINTR)continue;
        if(n<=0)break;
        count+=(size_t)n;
    }
    (*env)->ReleaseByteArrayElements(env,data,buf,JNI_ABORT);
    return (jint)count;
}
JNIEXPORT void JNICALL
Java_io_musab_arabicterminal_NativePty_resize(JNIEnv *env,jclass type,jlong handle,jint rows,jint cols) {
    (void)env;(void)type;
    Pty *pty=(Pty*)(intptr_t)handle;
    if(!pty||rows<1||cols<1)return;
    struct winsize sz={.ws_row=(unsigned short)rows,.ws_col=(unsigned short)cols};
    ioctl(pty->master,TIOCSWINSZ,&sz);
}
JNIEXPORT void JNICALL
Java_io_musab_arabicterminal_NativePty_terminate(JNIEnv *env,jclass type,jlong handle) {
    (void)env;(void)type;
    Pty *pty=(Pty*)(intptr_t)handle;
    if(pty&&pty->child>0)kill(-pty->child,SIGHUP);
}
JNIEXPORT void JNICALL
Java_io_musab_arabicterminal_NativePty_destroy(JNIEnv *env,jclass type,jlong handle) {
    (void)env;(void)type;
    Pty *pty=(Pty*)(intptr_t)handle;
    if(!pty)return;
    close(pty->master);
    int status;
    if(waitpid(pty->child,&status,WNOHANG)==0){
        kill(-pty->child,SIGHUP);
        for(int i=0;i<40;i++){
            pid_t result=waitpid(pty->child,&status,WNOHANG);
            if(result!=0)break;
            usleep(10000);
        }
    }
    free(pty);
}
