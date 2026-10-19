package com.jobwatch.app

/**
 * The app's sense of the time. Everything that schedules, stamps or measures a check reads it here,
 * so a test can run hours of checks on a simulated clock in a moment (see DeliveryTimeTest).
 */
object Clock {
    @Volatile
    var now: () -> Long = { System.currentTimeMillis() }
}
