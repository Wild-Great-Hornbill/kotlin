// !LANGUAGE: +InlineClasses
// IGNORE_BACKEND: JVM_IR

inline class Foo(val x: Int) {
    val prop: Int get() = 1
    val asThis: Foo get() = this
}