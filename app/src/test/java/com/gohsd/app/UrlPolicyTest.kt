package com.gohsd.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {
    private val policy = UrlPolicy("www.example.com")

    @Test fun sameHostIsInternal() = assertTrue(policy.isInternal("example.com"))
    @Test fun wwwIsInternal() = assertTrue(policy.isInternal("www.example.com"))
    @Test fun subdomainIsInternal() = assertTrue(policy.isInternal("api.example.com"))
    @Test fun otherHostIsExternal() = assertFalse(policy.isInternal("evil.com"))
    @Test fun lookalikeIsExternal() = assertFalse(policy.isInternal("notexample.com"))
    @Test fun nullIsExternal() = assertFalse(policy.isInternal(null))
}
