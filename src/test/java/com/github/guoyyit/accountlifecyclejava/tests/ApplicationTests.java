package com.github.guoyyit.accountlifecyclejava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * @author Vincent
 * @version 1.0
 */
@SpringBootTest
@TestPropertySource(properties = "account-lifecycle.data-dir=target/test-data/application-tests")
public class ApplicationTests {
    @Test
    public void contentLoad() {
    }
}
