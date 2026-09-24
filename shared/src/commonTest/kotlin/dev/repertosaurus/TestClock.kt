package dev.repertosaurus

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * **The JVM tests' one clock** (style review F27 N6): it reads [instant], which a test may move
 * between writes to tell one write's stamp from another's. A test that never moves it has a fixed
 * clock. Every JVM test builds its repository on one of these rather than on a private clock of
 * its own; the only exception is a test whose subject is a clock that advances by itself.
 */
internal class TestClock(var instant: Instant) : Clock {
    constructor(iso: String) : this(Instant.parse(iso))

    override fun now(): Instant = instant
}
