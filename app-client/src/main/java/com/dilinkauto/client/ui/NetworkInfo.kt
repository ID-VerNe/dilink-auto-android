package com.dilinkauto.client.ui

import com.dilinkauto.client.service.NetUtil

/** Network helpers for the phone UI (status card shows local IPs on port 9637). */
internal fun getLocalIpAddresses(): List<String> =
    NetUtil.localIpv4Addresses().map { "$it:9637" }
