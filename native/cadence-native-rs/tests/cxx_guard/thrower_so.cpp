// Test fixture only (never shipped): stands in for Apple's closed libraries, which throw C++
// exceptions out of a separate shared library built against the app's libc++_shared.so.
#include <stdexcept>
#include <pthread.h>
#include <string>

struct NotAStdException { int code; };  // thrown types Apple defines itself need not derive from std::exception

extern "C" void cpp_throw(void) { throw std::runtime_error("boom"); }
extern "C" void cpp_throw_int(void) { throw 42; }
extern "C" void cpp_throw_custom(void) { throw NotAStdException{7}; }
extern "C" void cpp_throw_message(void) {
    throw std::runtime_error(
        "request failed for https://play.itunes.apple.com/WebObjects/MZPlay.woa/music/fps?token=SECRETSECRETSECRETSECRETSECRET1234 "
        "user alice.smith@example.com");
}
extern "C" void cpp_crash(void) { *(volatile int*)0 = 1; }
extern "C" void cpp_ok(void) {}

// An exception thrown on a thread that only has C++ frames on its stack, like Apple's own worker
// threads: nothing can catch it, so std::terminate runs (the guard's terminate report).
static void* throwing_thread(void*) { throw std::runtime_error("boom"); }
extern "C" void cpp_spawn_throwing_thread(void) {
    pthread_t t;
    pthread_create(&t, nullptr, throwing_thread, nullptr);
    pthread_join(t, nullptr);
}
