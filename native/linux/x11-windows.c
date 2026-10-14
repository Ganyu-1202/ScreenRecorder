/* SPDX-License-Identifier: GPL-3.0-or-later */
#include <X11/Xlib.h>
#include <X11/Xatom.h>
#include <X11/Xutil.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
static unsigned char *property(Display *d, Window w, const char *name, unsigned long *count) {
    Atom type; int format; unsigned long remaining; unsigned char *data = NULL;
    if (XGetWindowProperty(d,w,XInternAtom(d,name,False),0,65536,False,AnyPropertyType,
            &type,&format,count,&remaining,&data) != Success) return NULL;
    return data;
}
static void print_safe(const unsigned char *s, unsigned long n) {
    for (unsigned long i=0; i<n && s[i]; i++) putchar(s[i]=='|' || s[i]=='\n' || s[i]=='\r' ? ' ' : s[i]);
}
int main(void) {
    Display *d = XOpenDisplay(NULL); if (!d) return 1;
    Window root = DefaultRootWindow(d); unsigned long count = 0;
    unsigned char *data = property(d,root,"_NET_CLIENT_LIST",&count);
    if (!data) { XCloseDisplay(d); return 0; }
    Window *windows = (Window *)data;
    for (unsigned long i=0; i<count; i++) {
        XWindowAttributes a; int x,y; Window ignored;
        if (!XGetWindowAttributes(d,windows[i],&a) || a.map_state != IsViewable || a.width<2 || a.height<2) continue;
        if (!XTranslateCoordinates(d,windows[i],root,0,0,&x,&y,&ignored)) continue;
        unsigned long n; unsigned char *title = property(d,windows[i],"_NET_WM_NAME",&n);
        if (!title) title = property(d,windows[i],"WM_NAME",&n);
        if (!title || !n) { if(title) XFree(title); continue; }
        XClassHint hint; memset(&hint,0,sizeof(hint));
        if (XGetClassHint(d,windows[i],&hint)) {
            const char *name=hint.res_class ? hint.res_class : "X11";
            print_safe((const unsigned char*)name,strlen(name));
            if(hint.res_name) XFree(hint.res_name);
            if(hint.res_class) XFree(hint.res_class);
        } else printf("X11");
        putchar('|'); print_safe(title,n); printf("|%d|%d|%d|%d\n",x,y,a.width,a.height);
        XFree(title);
    }
    XFree(data); XCloseDisplay(d); return 0;
}
