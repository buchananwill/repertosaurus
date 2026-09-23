package dev.repertosaurus.core

/**
 * **The one read-back of an enum stored by name** (style review F9 B3): the entry named [name], or
 * null when nothing was stored or the name is one this build does not know. Never a throw.
 */
public inline fun <reified E : Enum<E>> enumByNameOrNull(name: String?): E? =
    enumValues<E>().firstOrNull { it.name == name }

/** [enumByNameOrNull], with [default] in place of null. */
public inline fun <reified E : Enum<E>> enumByName(name: String?, default: E): E =
    enumByNameOrNull<E>(name) ?: default
