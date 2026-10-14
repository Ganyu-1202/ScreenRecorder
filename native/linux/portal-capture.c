/* SPDX-License-Identifier: GPL-3.0-or-later
 * ScreenCast portal client. The remote FD must be inherited by pipewiresrc;
 * a PipeWire node id alone does NOT grant access to a restricted stream.
 */
#include <gio/gio.h>
#include <gio/gunixfdlist.h>
#include <glib-unix.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/wait.h>

static GDBusConnection *bus;
static GMainLoop *loop;
static GVariant *response;
static char *session;
static GPid child;
static gboolean failed;
static void die(const char *message) { fprintf(stderr, "Portal: %s\n", message); exit(1); }
static void reply(GDBusConnection *c, const char *sender, const char *path,
                  const char *iface, const char *signal, GVariant *args, void *data) {
    (void)c; (void)sender; (void)path; (void)iface; (void)signal; (void)data;
    response = g_variant_ref(args); g_main_loop_quit(loop);
}
static gboolean timeout_request(void *data) { (void)data; g_main_loop_quit(loop); return G_SOURCE_REMOVE; }
static GVariant *request(const char *method, GVariant *params, const char *token) {
    char *sender = g_strdup(g_dbus_connection_get_unique_name(bus) + 1);
    for (char *p = sender; *p; ++p) if (*p == '.') *p = '_';
    char *path = g_strdup_printf("/org/freedesktop/portal/desktop/request/%s/%s", sender, token);
    guint sub = g_dbus_connection_signal_subscribe(bus, "org.freedesktop.portal.Desktop",
        "org.freedesktop.portal.Request", "Response", path, NULL, G_DBUS_SIGNAL_FLAGS_NONE, reply, NULL, NULL);
    GError *error = NULL;
    GVariant *ret = g_dbus_connection_call_sync(bus, "org.freedesktop.portal.Desktop",
        "/org/freedesktop/portal/desktop", "org.freedesktop.portal.ScreenCast", method,
        params, G_VARIANT_TYPE("(o)"), G_DBUS_CALL_FLAGS_NONE, 15000, NULL, &error);
    if (!ret) die(error->message);
    g_variant_unref(ret);
    guint timer = g_timeout_add_seconds(120, timeout_request, NULL);
    if (!response) g_main_loop_run(loop);
    if (response) g_source_remove(timer);
    g_dbus_connection_signal_unsubscribe(bus, sub);
    if (!response) {
        g_dbus_connection_call_sync(bus, "org.freedesktop.portal.Desktop", path,
            "org.freedesktop.portal.Request", "Close", NULL, NULL, G_DBUS_CALL_FLAGS_NONE, 3000, NULL, NULL);
        die("authorization timed out");
    }
    guint code; GVariant *results;
    g_variant_get(response, "(u@a{sv})", &code, &results);
    g_variant_unref(response); response = NULL;
    g_free(sender); g_free(path);
    if (code) die(code == 1 ? "selection cancelled" : "screen sharing denied");
    return results;
}
static void options(GVariantBuilder *b, const char *token) {
    g_variant_builder_init(b, G_VARIANT_TYPE_VARDICT);
    g_variant_builder_add(b, "{sv}", "handle_token", g_variant_new_string(token));
}
static gboolean stop_capture(void *data) {
    (void)data;
    if (child) kill(child, SIGTERM);
    g_main_loop_quit(loop); return G_SOURCE_REMOVE;
}
static void child_exit(GPid pid, gint status, void *data) {
    (void)data; failed = !WIFEXITED(status) || WEXITSTATUS(status) != 0;
    g_spawn_close_pid(pid); child = 0; g_main_loop_quit(loop);
}
static void closed(GDBusConnection *c, const char *sender, const char *path,
                   const char *iface, const char *signal, GVariant *args, void *data) {
    (void)c; (void)sender; (void)path; (void)iface; (void)signal; (void)args;
    failed = TRUE; stop_capture(data);
}
int main(int argc, char **argv) {
    if (argc != 5) die("usage: portal-capture FIFO FPS CURSOR screen|window");
    GError *error = NULL;
    bus = g_bus_get_sync(G_BUS_TYPE_SESSION, NULL, &error);
    if (!bus) die(error->message);
    loop = g_main_loop_new(NULL, FALSE);
    char *token = g_strdup_printf("screenrec_%u", (unsigned)getpid());
    GVariantBuilder b; options(&b, token);
    g_variant_builder_add(&b, "{sv}", "session_handle_token", g_variant_new_string(token));
    GVariant *r = request("CreateSession", g_variant_new("(a{sv})", &b), token);
    if (!g_variant_lookup(r, "session_handle", "s", &session)) die("missing session handle");
    g_variant_unref(r);
    char *select_token = g_strconcat(token, "_select", NULL); options(&b, select_token);
    g_variant_builder_add(&b, "{sv}", "types", g_variant_new_uint32(g_str_equal(argv[4], "window") ? 2 : 1));
    g_variant_builder_add(&b, "{sv}", "multiple", g_variant_new_boolean(FALSE));
    g_variant_builder_add(&b, "{sv}", "cursor_mode", g_variant_new_uint32(atoi(argv[3]) ? 2 : 1));
    r = request("SelectSources", g_variant_new("(oa{sv})", session, &b), select_token); g_variant_unref(r);
    char *start_token = g_strconcat(token, "_start", NULL); options(&b, start_token);
    r = request("Start", g_variant_new("(osa{sv})", session, "", &b), start_token);
    GVariant *streams = g_variant_lookup_value(r, "streams", G_VARIANT_TYPE("a(ua{sv})"));
    if (!streams || g_variant_n_children(streams) != 1) die("expected one selected stream");
    GVariant *stream = g_variant_get_child_value(streams, 0), *props; guint node;
    g_variant_get(stream, "(u@a{sv})", &node, &props);
    g_variant_unref(props); g_variant_unref(stream); g_variant_unref(streams); g_variant_unref(r);
    options(&b, token);
    GUnixFDList *fds = NULL;
    r = g_dbus_connection_call_with_unix_fd_list_sync(bus, "org.freedesktop.portal.Desktop",
        "/org/freedesktop/portal/desktop", "org.freedesktop.portal.ScreenCast", "OpenPipeWireRemote",
        g_variant_new("(oa{sv})", session, &b), G_VARIANT_TYPE("(h)"), G_DBUS_CALL_FLAGS_NONE,
        15000, NULL, &fds, NULL, &error);
    if (!r) die(error->message);
    gint index; g_variant_get(r, "(h)", &index);
    int fd = g_unix_fd_list_get(fds, index, &error); if (fd < 0) die(error->message);
    fcntl(fd, F_SETFD, 0);
    char *fdarg = g_strdup_printf("fd=%d", fd), *nodearg = g_strdup_printf("path=%u", node);
    char *caps = g_strdup_printf("video/x-raw,format=I420,framerate=%d/1", atoi(argv[2]));
    char *location = g_strconcat("location=", argv[1], NULL);
    char *gst[] = {"gst-launch-1.0", "-q", "pipewiresrc", fdarg, nodearg, "do-timestamp=true", "!",
        "videoconvert", "!", "videorate", "!", caps, "!", "y4menc", "!", "filesink", location, NULL};
    if (!g_spawn_async(NULL, gst, NULL, G_SPAWN_SEARCH_PATH | G_SPAWN_LEAVE_DESCRIPTORS_OPEN |
        G_SPAWN_DO_NOT_REAP_CHILD, NULL, NULL, &child, &error)) die(error->message);
    close(fd);
    g_child_watch_add(child, child_exit, NULL);
    g_unix_signal_add(SIGTERM, stop_capture, NULL); g_unix_signal_add(SIGINT, stop_capture, NULL);
    g_dbus_connection_signal_subscribe(bus, "org.freedesktop.portal.Desktop", "org.freedesktop.portal.Session",
        "Closed", session, NULL, G_DBUS_SIGNAL_FLAGS_NONE, closed, NULL, NULL);
    puts("READY"); fflush(stdout);
    g_main_loop_run(loop);
    if (child) { kill(child, SIGTERM); waitpid(child, NULL, 0); }
    g_dbus_connection_call_sync(bus, "org.freedesktop.portal.Desktop", session,
        "org.freedesktop.portal.Session", "Close", NULL, NULL, G_DBUS_CALL_FLAGS_NONE, 3000, NULL, NULL);
    return failed ? 1 : 0;
}
