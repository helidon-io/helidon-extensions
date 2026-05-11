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

import io.helidon.json.binding.JsonBinding;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class ComposedJsonBindingTest {

    private final JsonBinding jsonBinding = JsonBinding.create();

    @Test
    void oneOfDiscriminatorRoundTrip() {
        Cat cat = new Cat();
        cat.setKind("cat&special");
        cat.setWhiskers(7);

        String json = jsonBinding.serialize((Pet) cat, Pet.class);
        assertThat(json, containsString("\"kind\":\"cat&special\""));
        assertThat(json, containsString("\"whiskers\":7"));

        Pet pet = jsonBinding.deserialize("{\"kind\":\"cat&special\",\"whiskers\":7}", Pet.class);
        assertThat(pet, instanceOf(Cat.class));
        assertThat(((Cat) pet).getWhiskers(), is(7));
    }

    @Test
    void anyOfStructuralRoundTrip() {
        EmailContact emailContact = new EmailContact();
        emailContact.setEmail("user@example.com");

        String json = jsonBinding.serialize((Contact) emailContact, Contact.class);
        assertThat(json, containsString("\"email\":\"user@example.com\""));

        Contact contact = jsonBinding.deserialize("{\"email\":\"user@example.com\"}", Contact.class);
        assertThat(contact, instanceOf(EmailContact.class));
        assertThat(((EmailContact) contact).getEmail(), is("user@example.com"));
    }

    @Test
    void nullableUnionSupportsJsonNull() {
        NullablePet pet = jsonBinding.deserialize("null", NullablePet.class);
        assertThat(pet, is((NullablePet) null));

        String json = jsonBinding.serialize((NullablePet) null, NullablePet.class);
        assertThat(json, is("null"));
    }

    @Test
    void constrainedOneOfStructuralDeserializesUniqueBranch() {
        ScoreChoice lowScore = jsonBinding.deserialize("{\"score\":9}", ScoreChoice.class);
        assertThat(lowScore, instanceOf(LowScore.class));

        ScoreChoice highScore = jsonBinding.deserialize("{\"score\":10}", ScoreChoice.class);
        assertThat(highScore, instanceOf(HighScore.class));

        PatternChoice alphaCode = jsonBinding.deserialize("{\"code\":\"ABC\"}", PatternChoice.class);
        assertThat(alphaCode, instanceOf(AlphaCode.class));

        PatternChoice numericCode = jsonBinding.deserialize("{\"code\":\"123\"}", PatternChoice.class);
        assertThat(numericCode, instanceOf(NumericCode.class));
    }
}
