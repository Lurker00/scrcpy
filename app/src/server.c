#include "server.h"

#include <errno.h>
#include <inttypes.h>
#include <libgen.h>
#include <stdio.h>
#include <SDL2/SDL_assert.h>
#include <SDL2/SDL_timer.h>

#include "config.h"
#include "command.h"
#include "log.h"
#include "net.h"

#define SOCKET_NAME "scrcpy"
#define SERVER_FILENAME "scrcpy-server.jar"

#define DEFAULT_SERVER_PATH PREFIX "/share/scrcpy/" SERVER_FILENAME
#define DEVICE_SERVER_PATH "/data/local/tmp/" SERVER_FILENAME

static const char *
get_server_path(void) {
    const char *server_path_env = getenv("SCRCPY_SERVER_PATH");
    if (server_path_env) {
        LOGD("Using SCRCPY_SERVER_PATH: %s", server_path_env);
        // if the envvar is set, use it
        return server_path_env;
    }

#ifndef PORTABLE
    LOGD("Using server: " DEFAULT_SERVER_PATH);
    // the absolute path is hardcoded
    return DEFAULT_SERVER_PATH;
#else
    // use scrcpy-server.jar in the same directory as the executable
    char *executable_path = get_executable_path();
    if (!executable_path) {
        LOGE("Could not get executable path, "
             "using " SERVER_FILENAME " from current directory");
        // not found, use current directory
        return SERVER_FILENAME;
    }
    char *dir = dirname(executable_path);
    size_t dirlen = strlen(dir);

    // sizeof(SERVER_FILENAME) gives statically the size including the null byte
    size_t len = dirlen + 1 + sizeof(SERVER_FILENAME);
    char *server_path = SDL_malloc(len);
    if (!server_path) {
        LOGE("Could not alloc server path string, "
             "using " SERVER_FILENAME " from current directory");
        SDL_free(executable_path);
        return SERVER_FILENAME;
    }

    memcpy(server_path, dir, dirlen);
    server_path[dirlen] = PATH_SEPARATOR;
    memcpy(&server_path[dirlen + 1], SERVER_FILENAME, sizeof(SERVER_FILENAME));
    // the final null byte has been copied with SERVER_FILENAME

    SDL_free(executable_path);

    LOGD("Using server (portable): %s", server_path);
    return server_path;
#endif
}

static bool
push_server(const char *serial) {
    process_t process = adb_push(serial, get_server_path(), DEVICE_SERVER_PATH);
    return process_check_success(process, "adb push");
}

static bool
enable_tunnel_reverse(const char *serial, uint16_t local_port) {
    process_t process = adb_reverse(serial, SOCKET_NAME, local_port);
    return process_check_success(process, "adb reverse");
}

static bool
disable_tunnel_reverse(const char *serial) {
    process_t process = adb_reverse_remove(serial, SOCKET_NAME);
    return process_check_success(process, "adb reverse --remove");
}

static bool
enable_tunnel_forward(const char *serial, uint16_t local_port) {
    process_t process = adb_forward(serial, local_port, SOCKET_NAME);
    return process_check_success(process, "adb forward");
}

static bool
disable_tunnel_forward(const char *serial, uint16_t local_port) {
    process_t process = adb_forward_remove(serial, local_port);
    return process_check_success(process, "adb forward --remove");
}

static bool
enable_tunnel(struct server *server) {
    if (enable_tunnel_reverse(server->serial, server->local_port)) {
        return true;
    }

    LOGW("'adb reverse' failed, fallback to 'adb forward'");
    server->tunnel_forward = true;
    return enable_tunnel_forward(server->serial, server->local_port);
}

static bool
disable_tunnel(struct server *server) {
    if (server->tunnel_forward && server->tunnel_enabled) {
        return disable_tunnel_forward(server->serial, server->local_port);
    }
    return disable_tunnel_reverse(server->serial);
}

static process_t
execute_server(struct server *server, const struct server_params *params, bool isIP) {
    char max_size_string[6];
    char bit_rate_string[11];
    sprintf(max_size_string, "%"PRIu16, params->max_size);
    sprintf(bit_rate_string, "%"PRIu32, params->bit_rate);
    char density_string[8+5+1];
    sprintf(density_string,  "density=%"PRIu16, params->density);
    char size_string[5+5+1+5+1];
    snprintf(size_string, sizeof(size_string), "size=%s", params->size ? params->size : "0:0");
    char tablet_string[7+5+1];
    sprintf(tablet_string, "tablet=%s", params->tablet ? "true" : "false");
    char local_port_string[5+5+1];
    sprintf(local_port_string, "port=%u", isIP ? params->local_port : 0);
    char ime_string[7+5+1];
    sprintf(ime_string, "useIME=%s", params->useIME ? "true" : "false");
    char hwolv_string[18+5+1];
    sprintf(hwolv_string, "disableHWOverlays=%s", params->disableHWOverlays ? "true" : "false");

    const char *const cmd[] = {
        "shell",
        "CLASSPATH=/data/local/tmp/" SERVER_FILENAME,
        "app_process",
        "/", // unused
        "com.genymobile.scrcpy.Server",
        max_size_string,
        bit_rate_string,
        server->tunnel_forward ? "true" : "false",
        params->crop ? params->crop : "-",
        "true", // always send frame meta (packet boundaries + timestamp)
        params->control ? "true" : "false",
        density_string,
        size_string,
        tablet_string,
        local_port_string,
        ime_string,
        hwolv_string,
#ifdef WINDOWS_NOCONSOLE
        "fork",
#else
        "forkd",
#endif
    };
    return adb_execute(server->serial, cmd, sizeof(cmd) / sizeof(cmd[0]));
}

static socket_t
listen_on_port(uint16_t port) {
    return net_listen(IPV4_LOCALHOST, port, 1);
}

static socket_t
connect_and_read_byte(uint32_t addr, uint16_t port) {
    socket_t socket = net_connect(addr, port);
    if (socket == INVALID_SOCKET) {
        return INVALID_SOCKET;
    }

    char byte;
    // the connection may succeed even if the server behind the "adb tunnel"
    // is not listening, so read one byte to detect a working connection
    if (net_recv(socket, &byte, 1) != 1) {
        // the server is not listening yet behind the adb tunnel
        net_close(socket);
        return INVALID_SOCKET;
    }
    return socket;
}

static socket_t
connect_to_server(uint32_t addr, uint16_t port, uint32_t attempts, uint32_t delay) {
    do {
        LOGD("Remaining connection attempts: %d", (int) attempts);
        socket_t socket = connect_and_read_byte(addr, port);
        if (socket != INVALID_SOCKET) {
            // it worked!
            return socket;
        }
        if (attempts) {
            SDL_Delay(delay);
        }
    } while (--attempts > 0);
    return INVALID_SOCKET;
}

static void
close_socket(socket_t *socket) {
    SDL_assert(*socket != INVALID_SOCKET);
    net_shutdown(*socket, SHUT_RDWR);
    if (!net_close(*socket)) {
        LOGW("Could not close socket");
        return;
    }
    *socket = INVALID_SOCKET;
}

void
server_init(struct server *server) {
    *server = (struct server) SERVER_INITIALIZER;
}

static uint32_t
serial2addr(const char *serial) {
    uint32_t addr = 0;
    for (int i=0; i<4; i++) {
        addr <<= 8;
        addr += strtol(serial, (char**)&serial, 10);
        serial++;
    }
    return addr;
}

bool
server_start(struct server *server, const char *serial,
             const struct server_params *params) {
    server->local_port = params->local_port;

    if (serial) {
        server->serial = SDL_strdup(serial);
        if (!server->serial) {
            return false;
        }
    }

    bool isIP = adb_connect(serial);

    if (!push_server(serial)) {
        SDL_free(server->serial);
        return false;
    }

    if (isIP) {
        server->addr = serial2addr(serial);
        server->tunnel_forward = true;
    }
    else if (!enable_tunnel(server)) {
        SDL_free(server->serial);
        return false;
    }

    // if "adb reverse" does not work (e.g. over "adb connect"), it fallbacks to
    // "adb forward", so the app socket is the client
    if (!server->tunnel_forward) {
        // At the application level, the device part is "the server" because it
        // serves video stream and control. However, at the network level, the
        // client listens and the server connects to the client. That way, the
        // client can listen before starting the server app, so there is no
        // need to try to connect until the server socket is listening on the
        // device.

        server->server_socket = listen_on_port(params->local_port);
        if (server->server_socket == INVALID_SOCKET) {
            LOGE("Could not listen on port %" PRIu16, params->local_port);
            disable_tunnel(server);
            SDL_free(server->serial);
            return false;
        }
    }

    // server will connect to our server socket
    server->process = execute_server(server, params, isIP);

    if (server->process == PROCESS_NONE) {
        if (!server->tunnel_forward) {
            close_socket(&server->server_socket);
        }
        disable_tunnel(server);
        SDL_free(server->serial);
        return false;
    }

    if (!isIP) server->tunnel_enabled = true;

    return true;
}

bool
server_connect_to(struct server *server) {
    if (!server->tunnel_forward) {
        server->video_socket = net_accept(server->server_socket);
        if (server->video_socket == INVALID_SOCKET) {
            return false;
        }

        server->control_socket = net_accept(server->server_socket);
        if (server->control_socket == INVALID_SOCKET) {
            // the video_socket will be clean up on destroy
            return false;
        }

        // we don't need the server socket anymore
        close_socket(&server->server_socket);
    } else {
        LOGD("Trying to connect...");
        uint32_t attempts = 100;
        uint32_t delay = 100; // ms
        server->video_socket =
            connect_to_server(server->addr, server->local_port, attempts, delay);
        if (server->video_socket == INVALID_SOCKET) {
            LOGE("Could not connect video");
            return false;
        }

        // we know that the device is listening, we don't need several attempts
        server->control_socket =
            net_connect(server->addr, server->local_port);
        if (server->control_socket == INVALID_SOCKET) {
            LOGE("Could not connect control");
            return false;
        }
        LOGD("Connected!");
    }

    // we don't need the adb tunnel anymore
    if (server->tunnel_enabled) {
        disable_tunnel(server); // ignore failure
        server->tunnel_enabled = false;
    }

#ifdef WINDOWS_NOCONSOLE
    adb_disconnect(server->serial);
#endif
   return true;
}

void
server_stop(struct server *server) {
    if (server->server_socket != INVALID_SOCKET) {
        close_socket(&server->server_socket);
    }
    if (server->video_socket != INVALID_SOCKET) {
        close_socket(&server->video_socket);
    }
    if (server->control_socket != INVALID_SOCKET) {
        close_socket(&server->control_socket);
    }

    SDL_assert(server->process != PROCESS_NONE);

    if (!cmd_terminate(server->process)) {
        LOGW("Could not terminate server");
    }

    cmd_simple_wait(server->process, NULL); // ignore exit code
    LOGD("Server terminated");

    if (server->tunnel_enabled) {
        // ignore failure
        disable_tunnel(server);
    }
#ifndef WINDOWS_NOCONSOLE
    adb_disconnect(server->serial);
#endif
}

void
server_destroy(struct server *server) {
    SDL_free(server->serial);
}
