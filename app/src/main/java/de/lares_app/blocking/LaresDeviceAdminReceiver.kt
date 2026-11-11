/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.blocking

import android.app.admin.DeviceAdminReceiver

/**
 * Device administrator for the "prevent uninstalling" protection: while active, Android refuses to
 * uninstall Lares. Uses no admin policies. The tamper alert on deactivation follows with the
 * protection services.
 */
class LaresDeviceAdminReceiver : DeviceAdminReceiver()
