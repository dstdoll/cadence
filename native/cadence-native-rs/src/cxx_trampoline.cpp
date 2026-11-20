// Source of cxx_trampoline.s (the compiled form is embedded with global_asm! in cxx_guard.rs, so the
// build needs no C++ compiler). Regenerate with native/regen-cxx-trampoline.sh. This file is
// documentation + the generator's input; build.rs does not compile it.
//
// It must be compiled against the SAME libc++ the Apple libraries throw with (the app ships
// libc++_shared.so): the catch frame's personality routine and __cxa_* come from that runtime. A
// Rust catch frame cannot do this (see cxx_guard.rs).
#include <cstdlib>
#include <cxxabi.h>
#include <exception>
#include <typeinfo>

struct CxxThrow {
    char type[96];  // mangled dynamic type name, e.g. "St13runtime_error"
    char what[224]; // std::exception::what(), empty for non-std exceptions
};

static void copy(char* dst, unsigned cap, const char* src) {
    unsigned i = 0;
    if (src) for (; i + 1 < cap && src[i]; ++i) dst[i] = src[i];
    dst[i] = 0;
}

// Runs fn(ctx). Returns 0 normally; -1 if it threw a std::exception (type + what filled in);
// -2 if it threw anything else (type filled in when the runtime knows it).
extern "C" int cadence_try_call(void (*fn)(void*), void* ctx, CxxThrow* out) {
    try {
        fn(ctx);
        return 0;
    } catch (const std::exception& e) {
        copy(out->type, sizeof out->type, typeid(e).name());
        copy(out->what, sizeof out->what, e.what());
        return -1;
    } catch (...) {
        const std::type_info* t = abi::__cxa_current_exception_type();
        copy(out->type, sizeof out->type, t ? t->name() : nullptr);
        out->what[0] = 0;
        return -2;
    }
}

// Implemented in Rust (events.rs): logs the exception that is about to terminate the process.
extern "C" void cadence_terminate_report(const char* type, const char* what);

static std::terminate_handler g_prev;
static volatile int g_busy;

// std::terminate runs at the throw site when no handler exists, on whatever thread threw (including
// Apple's own threads, where nothing of ours can catch). Record what was thrown, then continue with
// the previous handler (which aborts), so the log says why the process died.
static void cadence_terminate_handler() {
    if (!g_busy) {
        g_busy = 1;
        CxxThrow t;
        t.type[0] = 0;
        t.what[0] = 0;
        const std::type_info* ti = abi::__cxa_current_exception_type();
        if (ti) {
            try {
                throw;
            } catch (const std::exception& e) {
                copy(t.type, sizeof t.type, typeid(e).name());
                copy(t.what, sizeof t.what, e.what());
            } catch (...) {
                copy(t.type, sizeof t.type, ti->name());
            }
        }
        cadence_terminate_report(t.type, t.what);
    }
    if (g_prev) g_prev();
    abort();
}

extern "C" void cadence_install_terminate_handler() { g_prev = std::set_terminate(cadence_terminate_handler); }
