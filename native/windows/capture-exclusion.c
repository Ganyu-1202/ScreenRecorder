#include <windows.h>
#include <jni.h>
#include <wchar.h>
typedef struct { const WCHAR *title; HWND window; } Search;
static BOOL CALLBACK visit(HWND h, LPARAM value) {
    Search *s=(Search*)value; DWORD pid=0; WCHAR title[256];
    GetWindowThreadProcessId(h,&pid);
    if(pid==GetCurrentProcessId() && GetWindowTextW(h,title,256) && wcscmp(title,s->title)==0) { s->window=h; return FALSE; }
    return TRUE;
}
JNIEXPORT jint JNICALL Java_org_firefly_screenrec_ui_overlay_CaptureExclusion_applyNative(JNIEnv *env,jclass cls,jstring title,jboolean excluded) {
    const jchar *text=(*env)->GetStringChars(env,title,NULL);
    jsize len=(*env)->GetStringLength(env,title); WCHAR name[256];
    if(!text) return ERROR_NOT_ENOUGH_MEMORY;
    if(len>=256) { (*env)->ReleaseStringChars(env,title,text); return ERROR_INVALID_PARAMETER; }
    memcpy(name,text,len*sizeof(WCHAR)); name[len]=0;
    (*env)->ReleaseStringChars(env,title,text);
    Search s={name,NULL}; EnumWindows(visit,(LPARAM)&s);
    if(!s.window) return ERROR_INVALID_WINDOW_HANDLE;
    if(!SetWindowDisplayAffinity(s.window,excluded?0x11:0)) return GetLastError();
    DWORD affinity=0;
    if(!GetWindowDisplayAffinity(s.window,&affinity)) return GetLastError();
    return affinity==(excluded?0x11:0)?0:ERROR_NOT_SUPPORTED;
}
