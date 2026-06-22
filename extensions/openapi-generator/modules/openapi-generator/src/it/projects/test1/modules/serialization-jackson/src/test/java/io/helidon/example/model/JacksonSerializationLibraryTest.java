/*
 * Copyright (c) 2026 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.helidon.example.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class JacksonSerializationLibraryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void discriminatorModelRoundTrips() throws Exception {
        AuthorDefinedExpiryDetails value = new AuthorDefinedExpiryDetails();
        value.expiryDuration(7);

        String json = mapper.writerFor(ApprovalInvalidationTypeDetails.class).writeValueAsString(value);
        assertThat(json, containsString("\"approvalInvalidationType\":\"AUTHOR_DEFINED_TIME_EXPIRY\""));
        assertThat(json, containsString("\"expiryDuration\":7"));
        assertThat(countOccurrences(json, "\"approvalInvalidationType\""), is(1));

        ApprovalInvalidationTypeDetails parsed = mapper.readValue("""
                {"approvalInvalidationType":"AUTHOR_DEFINED_TIME_EXPIRY","expiryDuration":7}
                """, ApprovalInvalidationTypeDetails.class);

        assertThat(parsed, instanceOf(AuthorDefinedExpiryDetails.class));
        assertThat(((AuthorDefinedExpiryDetails) parsed).expiryDuration(), is(7));
        assertThat(parsed.approvalInvalidationType(),
                   is(ApprovalInvalidationTypeDetails.ApprovalInvalidationTypeEnum.AUTHOR_DEFINED_TIME_EXPIRY));
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int index = text.indexOf(token);
        while (index >= 0) {
            count++;
            index = text.indexOf(token, index + token.length());
        }
        return count;
    }
}
