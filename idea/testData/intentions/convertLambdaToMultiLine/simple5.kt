// WITH_RUNTIME
fun test(list: List<String>) {
    list.forEachIndexed { index, s -> println(index) }<caret>
}