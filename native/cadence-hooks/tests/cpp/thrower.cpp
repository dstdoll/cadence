#include <stdexcept>
#include <string>
struct Custom { int code; };
// Throws and catches inside this library: the throw goes through this library's __cxa_throw PLT slot.
extern "C" int throw_and_catch() {
    try { throw std::runtime_error("boom"); } catch (const std::exception&) { return 42; }
    return 0;
}
// The same exception type from a different call site.
extern "C" int throw_runtime_elsewhere() {
    try { throw std::runtime_error("elsewhere"); } catch (const std::exception&) { return 44; }
    return 0;
}
extern "C" int throw_custom_and_catch() {
    try { throw Custom{7}; } catch (const Custom& c) { return c.code; }
    return 0;
}
// Throws through two frames of the caller (a Rust callback in between) before being caught here.
extern "C" int call_then_catch(void (*cb)()) {
    try { cb(); } catch (const std::exception&) { return 43; }
    return 0;
}
extern "C" void throw_now() { throw std::logic_error("from callee"); }
