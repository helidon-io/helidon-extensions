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
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComposedJsonBindingTest {

    private final JsonBinding jsonBinding = JsonBinding.create();

    @Test
    void oneOfDiscriminatorRoundTrip() {
        Cat cat = new Cat();
        cat.whiskers(7);

        String json = jsonBinding.serialize((Pet) cat, Pet.class);
        assertThat(occurrences(json, "\"kind\""), is(1));
        assertThat(json, containsString("\"kind\":\"cat$special\""));
        assertThat(json, containsString("\"whiskers\":7"));
        assertThat(cat.kind(), is("cat$special"));

        Pet pet = jsonBinding.deserialize("{\"whiskers\":7,\"kind\":\"cat$special\"}", Pet.class);
        assertThat(pet, instanceOf(Cat.class));
        assertThat(((Cat) pet).whiskers(), is(7));
    }

    @Test
    void directMemberBindingPreservesDiscriminator() {
        Cat cat = new Cat();
        cat.whiskers(7);

        String json = jsonBinding.serialize(cat, Cat.class);
        assertThat(occurrences(json, "\"kind\""), is(1));
        assertThat(json, containsString("\"kind\":\"cat$special\""));

        Cat restored = jsonBinding.deserialize("{\"kind\":\"cat$special\",\"whiskers\":7}", Cat.class);
        assertThat(restored.kind(), is("cat$special"));
        assertThat(restored.whiskers(), is(7));

        assertThrows(RuntimeException.class,
                     () -> jsonBinding.deserialize("{\"kind\":\"dog\",\"whiskers\":7}", Cat.class));
    }

    @Test
    void everyOneOfMemberRoundTripsWithItsCanonicalAlias() {
        Dog dog = new Dog();
        dog.bark(true);

        String json = jsonBinding.serialize((Pet) dog, Pet.class);
        assertThat(occurrences(json, "\"kind\""), is(1));
        assertThat(json, containsString("\"kind\":\"dog\""));

        Pet restored = jsonBinding.deserialize(json, Pet.class);
        assertThat(restored, instanceOf(Dog.class));
        assertThat(((Dog) restored).bark(), is(true));
    }

    @Test
    void metadataDiscriminatorIsRequired() {
        assertThrows(RuntimeException.class,
                     () -> jsonBinding.deserialize("{\"alpha\":\"value\"}", MetadataChoice.class));
    }

    @Test
    void everyMetadataMemberRoundTripsWithItsCanonicalAlias() {
        MetadataAlpha alpha = new MetadataAlpha();
        alpha.alpha("a");
        String alphaJson = jsonBinding.serialize((MetadataChoice) alpha, MetadataChoice.class);
        assertThat(alphaJson, containsString("\"category\":\"alpha.v1\""));
        assertThat(jsonBinding.deserialize(alphaJson, MetadataChoice.class), instanceOf(MetadataAlpha.class));

        MetadataBeta beta = new MetadataBeta();
        beta.beta("b");
        String betaJson = jsonBinding.serialize((MetadataChoice) beta, MetadataChoice.class);
        assertThat(betaJson, containsString("\"category\":\"MetadataBeta\""));
        assertThat(jsonBinding.deserialize(betaJson, MetadataChoice.class), instanceOf(MetadataBeta.class));
    }

    @Test
    void layeredAllOfRoundTripsThroughRootBase() {
        LayeredLeaf leaf = new LayeredLeaf();
        leaf.middle("middle");
        leaf.leaf("leaf-value");

        String json = jsonBinding.serialize((LayeredBase) leaf, LayeredBase.class);
        assertThat(occurrences(json, "\"kind\""), is(1));
        assertThat(json, containsString("\"kind\":\"leaf\""));

        LayeredBase restored = jsonBinding.deserialize(json, LayeredBase.class);
        assertThat(restored, instanceOf(LayeredLeaf.class));
        assertThat(((LayeredLeaf) restored).middle(), is("middle"));
        assertThat(((LayeredLeaf) restored).leaf(), is("leaf-value"));
    }

    @Test
    void oneOfRejectsMissingAndUnknownDiscriminators() {
        assertThrows(RuntimeException.class, () -> jsonBinding.deserialize("{\"whiskers\":7}", Pet.class));
        assertThrows(RuntimeException.class,
                     () -> jsonBinding.deserialize("{\"kind\":\"unknown\",\"whiskers\":7}", Pet.class));
    }

    @Test
    void anyOfStructuralRoundTrip() {
        EmailContact emailContact = new EmailContact();
        emailContact.email("user@example.com");

        String json = jsonBinding.serialize((Contact) emailContact, Contact.class);
        assertThat(json, containsString("\"email\":\"user@example.com\""));

        Contact contact = jsonBinding.deserialize("{\"email\":\"user@example.com\"}", Contact.class);
        assertThat(contact, instanceOf(EmailContact.class));
        assertThat(((EmailContact) contact).email(), is("user@example.com"));
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

    private int occurrences(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }
}
