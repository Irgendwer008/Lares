/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class DontKillMyAppTest {
    @Test fun knownManufacturersGetTheirPage() {
        assertEquals("https://dontkillmyapp.com/samsung", DontKillMyApp.url("samsung"))
        assertEquals("https://dontkillmyapp.com/xiaomi", DontKillMyApp.url("Xiaomi"))
        assertEquals("https://dontkillmyapp.com/huawei", DontKillMyApp.url("HUAWEI"))
        assertEquals("https://dontkillmyapp.com/hmd-global", DontKillMyApp.url("HMD Global"))
    }

    @Test fun unknownOrEmptyManufacturersGetTheStartPage() {
        assertEquals("https://dontkillmyapp.com/", DontKillMyApp.url("Fairphone"))
        assertEquals("https://dontkillmyapp.com/", DontKillMyApp.url(""))
    }
}
