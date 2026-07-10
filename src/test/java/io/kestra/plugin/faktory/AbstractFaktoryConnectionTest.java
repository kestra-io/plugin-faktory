package io.kestra.plugin.faktory;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class AbstractFaktoryConnectionTest {

    @Test
    void hashPassword_singleIteration_matchesPlainSha256() {
        var hash = AbstractFaktoryConnection.hashPassword("password123", "abcdef", 1);

        assertThat(hash, is("b01c6f769fca6f7a3df0eaefca631958ce2d43dc2c28d1834fbf432f64522e4a"));
    }

    @Test
    void hashPassword_multipleIterations_rehashesTheRawDigest() {
        var hash = AbstractFaktoryConnection.hashPassword("password123", "abcdef", 3);

        assertThat(hash, is("d2e1f2e8b0c26dbe6b4e7334e2f95054b50ce66bf3b60f0647e2551188b921f8"));
    }

    @Test
    void hashPassword_zeroOrNegativeIterations_behavesAsSingleHash() {
        var hash = AbstractFaktoryConnection.hashPassword("password123", "abcdef", 0);

        assertThat(hash, is("b01c6f769fca6f7a3df0eaefca631958ce2d43dc2c28d1834fbf432f64522e4a"));
    }
}
