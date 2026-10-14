/* SPDX-License-Identifier: GPL-3.0-or-later
 * Dynamically load AppIndicator: build requires GTK headers, runtime library
 * and its transitive dependencies are shipped by the AppImage recipe.
 */
#include <gtk/gtk.h>
#include <glib-unix.h>
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>
static GtkWidget *record_item, *stop_item;
static void action(GtkMenuItem *item, gpointer data) {
    (void)item; puts((const char *)data); fflush(stdout);
}
static gboolean quit(gpointer data) { (void)data; gtk_main_quit(); return G_SOURCE_REMOVE; }
static gboolean input(GIOChannel *channel, GIOCondition condition, gpointer data) {
    (void)data;
    if (condition & (G_IO_HUP | G_IO_ERR)) return quit(NULL);
    gchar *line = NULL; gsize size;
    if (g_io_channel_read_line(channel, &line, &size, NULL, NULL) == G_IO_STATUS_NORMAL) {
        gboolean recording = g_str_has_prefix(line, "recording");
        gtk_widget_set_sensitive(record_item, !recording);
        gtk_widget_set_sensitive(stop_item, recording);
        if (g_str_has_prefix(line, "quit")) { g_free(line); return quit(NULL); }
        g_free(line);
    }
    return G_SOURCE_CONTINUE;
}
static GtkWidget *add(GtkWidget *menu, const char *label, const char *command) {
    GtkWidget *item = gtk_menu_item_new_with_label(label);
    g_signal_connect(item, "activate", G_CALLBACK(action), (gpointer)command);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), item); return item;
}
int main(int argc, char **argv) {
    if (argc != 3) { fprintf(stderr, "usage: tray-indicator ICON zh|en\n"); return 1; }
    if (!gtk_init_check(&argc, &argv)) return 1;
    void *lib = dlopen("libayatana-appindicator3.so.1", RTLD_NOW);
    if (!lib) lib = dlopen("libappindicator3.so.1", RTLD_NOW);
    if (!lib) { fprintf(stderr,"AppIndicator library missing\n"); return 1; }
    gpointer (*create)(const gchar *,const gchar *,int) = dlsym(lib,"app_indicator_new");
    void (*set_status)(gpointer,int) = dlsym(lib,"app_indicator_set_status");
    void (*set_menu)(gpointer,GtkMenu *) = dlsym(lib,"app_indicator_set_menu");
    void (*set_title)(gpointer,const gchar *) = dlsym(lib,"app_indicator_set_title");
    if (!create || !set_status || !set_menu || !set_title) return 1;
    gpointer indicator = create("screenrecorder",argv[1],0);
    if (!indicator) return 1;
    gboolean zh = !strcmp(argv[2],"zh");
    GtkWidget *menu = gtk_menu_new();
    add(menu, zh ? "打开 ScreenRecorder" : "Open ScreenRecorder", "open");
    record_item = add(menu, zh ? "开始录制" : "Record display", "record");
    stop_item = add(menu, zh ? "停止录制" : "Stop recording", "stop");
    gtk_widget_set_sensitive(stop_item,FALSE);
    add(menu, zh ? "打开录制文件夹" : "Open recordings folder", "folder");
    gtk_menu_shell_append(GTK_MENU_SHELL(menu),gtk_separator_menu_item_new());
    add(menu, zh ? "退出" : "Quit", "quit");
    gtk_widget_show_all(menu);
    set_title(indicator,"ScreenRecorder"); set_menu(indicator,GTK_MENU(menu)); set_status(indicator,1);
    GIOChannel *channel = g_io_channel_unix_new(0);
    g_io_add_watch(channel,G_IO_IN | G_IO_HUP | G_IO_ERR,input,NULL);
    g_unix_signal_add(SIGTERM,quit,NULL); g_unix_signal_add(SIGINT,quit,NULL);
    puts("READY"); fflush(stdout);
    gtk_main(); g_io_channel_unref(channel); g_object_unref(indicator); dlclose(lib);
    return 0;
}
