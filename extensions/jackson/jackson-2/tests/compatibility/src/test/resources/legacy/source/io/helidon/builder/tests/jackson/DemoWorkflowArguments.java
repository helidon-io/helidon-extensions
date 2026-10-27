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

package io.helidon.builder.tests.jackson;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import io.helidon.builder.api.Prototype;
import io.helidon.common.Generated;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.OptBoolean;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

/**
 * Interface generated from blueprint {@code io.helidon.builder.tests.jackson.DemoWorkflowArgumentsBlueprint}. Please add javadoc to blueprint, as it is currently missing.
 *
 * @see #builder()
 * @see #create()
 */
@Generated(
    value = "io.helidon.builder.codegen.BuilderCodegen",
    trigger = "io.helidon.builder.tests.jackson.DemoWorkflowArgumentsBlueprint")
@JsonDeserialize(builder = DemoWorkflowArguments.Builder.class)
@JsonPOJOBuilder(withPrefix = "set")
public interface DemoWorkflowArguments extends DemoWorkflowArgumentsBlueprint, Prototype.Api {

    /**
     * Create a new fluent API builder to customize configuration.
     *
     * @return a new builder
     */
    static DemoWorkflowArguments.Builder builder() {
        return new DemoWorkflowArguments.Builder();
    }

    /**
     * Create a new fluent API builder from an existing instance.
     *
     * @param instance an existing instance used as a base for the builder
     * @return a builder based on an instance
     */
    static DemoWorkflowArguments.Builder builder(DemoWorkflowArguments instance) {
        return DemoWorkflowArguments.builder().from(instance);
    }

    /**
     * Create a new instance with default values.
     *
     * @return a new instance
     */
    static DemoWorkflowArguments create() {
        return DemoWorkflowArguments.builder().buildPrototype();
    }

    /**
     *
     */
    @JsonIgnore(true)
    default Map<String, String> getDecorations() {
        return DemoWorkflowArgumentsBlueprint.super.getDecorations();
    }

    /**
     * Size option.
     *
     * @return the size option
     */
    @Override
    @JsonProperty(
        value = "",
        namespace = "",
        required = false,
        isRequired = OptBoolean.DEFAULT,
        index = -1,
        defaultValue = "",
        access = JsonProperty.Access.AUTO)
    long getSize();

    /**
     * Encrypted option.
     *
     * @return the encrypted option
     */
    @Override
    @JsonProperty(
        value = "",
        namespace = "",
        required = false,
        isRequired = OptBoolean.DEFAULT,
        index = -1,
        defaultValue = "",
        access = JsonProperty.Access.AUTO)
    boolean isEncrypted();

    /**
     * WorkflowId option.
     *
     * @return the workflowId option
     */
    @Override
    @JsonProperty(
        value = "",
        namespace = "",
        required = false,
        isRequired = OptBoolean.DEFAULT,
        index = -1,
        defaultValue = "",
        access = JsonProperty.Access.AUTO)
    int getWorkflowId();

    /**
     * Label option.
     *
     * @return the label option
     */
    @Override
    @JsonProperty(
        value = "",
        namespace = "",
        required = false,
        isRequired = OptBoolean.DEFAULT,
        index = -1,
        defaultValue = "",
        access = JsonProperty.Access.AUTO)
    Optional<String> getLabel();

    /**
     * Fluent API builder base for {@link DemoWorkflowArguments}.
     *
     * @param <BUILDER> type of the builder extending this abstract builder
     * @param <PROTOTYPE> type of the prototype interface that would be built by {@link #buildPrototype()}
     */
    abstract class BuilderBase<BUILDER extends DemoWorkflowArguments.BuilderBase<BUILDER, PROTOTYPE>, PROTOTYPE extends DemoWorkflowArguments> implements Prototype.Builder<BUILDER, PROTOTYPE> {

        private boolean encrypted;
        private int workflowId;
        private long size;
        private String label;

        /**
         * Protected to support extensibility.
         */
        protected BuilderBase() {
        }

        /**
         * Update this builder from an existing prototype instance. This method disables automatic service discovery.
         *
         * @param prototype existing prototype to update this builder from
         * @return updated builder instance
         */
        public BUILDER from(DemoWorkflowArguments prototype) {
            setSize(prototype.getSize());
            setEncrypted(prototype.isEncrypted());
            setWorkflowId(prototype.getWorkflowId());
            setLabel(prototype.getLabel());
            return self();
        }

        /**
         * Update this builder from an existing prototype builder instance.
         *
         * @param builder existing builder prototype to update this builder from
         * @return updated builder instance
         */
        public BUILDER from(DemoWorkflowArguments.BuilderBase<?, ?> builder) {
            setSize(builder.getSize());
            setEncrypted(builder.isEncrypted());
            setWorkflowId(builder.getWorkflowId());
            builder.getLabel().ifPresent(this::setLabel);
            return self();
        }

        /**
         * Size option.
         *
         * @param size the size option
         * @return updated builder instance
         * @see #getSize()
         */
        @JsonProperty(
            value = "",
            namespace = "",
            required = false,
            isRequired = OptBoolean.DEFAULT,
            index = -1,
            defaultValue = "",
            access = JsonProperty.Access.AUTO)
        public BUILDER setSize(long size) {
            this.size = size;
            return self();
        }

        /**
         * Encrypted option.
         *
         * @param encrypted the encrypted option
         * @return updated builder instance
         * @see #isEncrypted()
         */
        @JsonProperty(
            value = "",
            namespace = "",
            required = false,
            isRequired = OptBoolean.DEFAULT,
            index = -1,
            defaultValue = "",
            access = JsonProperty.Access.AUTO)
        public BUILDER setEncrypted(boolean encrypted) {
            this.encrypted = encrypted;
            return self();
        }

        /**
         * WorkflowId option.
         *
         * @param workflowId the workflowId option
         * @return updated builder instance
         * @see #getWorkflowId()
         */
        @JsonProperty(
            value = "",
            namespace = "",
            required = false,
            isRequired = OptBoolean.DEFAULT,
            index = -1,
            defaultValue = "",
            access = JsonProperty.Access.AUTO)
        public BUILDER setWorkflowId(int workflowId) {
            this.workflowId = workflowId;
            return self();
        }

        /**
         * Clear existing value of label.
         *
         * @return updated builder instance
         * @see #getLabel()
         */
        public BUILDER clearLabel() {
            this.label = null;
            return self();
        }

        /**
         * Label option.
         *
         * @param label the label option
         * @return updated builder instance
         * @see #getLabel()
         */
        @JsonProperty(
            value = "",
            namespace = "",
            required = false,
            isRequired = OptBoolean.DEFAULT,
            index = -1,
            defaultValue = "",
            access = JsonProperty.Access.AUTO)
        public BUILDER setLabel(String label) {
            Objects.requireNonNull(label);
            this.label = label;
            return self();
        }

        /**
         * Size option.
         *
         * @return the size option
         */
        public long getSize() {
            return size;
        }

        /**
         * Encrypted option.
         *
         * @return the encrypted option
         */
        public boolean isEncrypted() {
            return encrypted;
        }

        /**
         * WorkflowId option.
         *
         * @return the workflowId option
         */
        public int getWorkflowId() {
            return workflowId;
        }

        /**
         * Label option.
         *
         * @return the label option
         */
        public Optional<String> getLabel() {
            return Optional.ofNullable(label);
        }

        @Override
        public String toString() {
            return "DemoWorkflowArgumentsBuilder{"
                    + "size=" + size + ","
                    + "encrypted=" + encrypted + ","
                    + "workflowId=" + workflowId + ","
                    + "label=" + label
                    + "}";
        }

        /**
         * Handles providers and decorators.
         */
        protected void preBuildPrototype() {
        }

        /**
         * Validates required properties.
         */
        protected void validatePrototype() {
        }

        /**
         * Label option.
         *
         * @param label the label option
         * @return updated builder instance
         * @see #getLabel()
         */
        BUILDER setLabel(Optional<String> label) {
            Objects.requireNonNull(label);
            this.label = label.orElse(this.label);
            return self();
        }

        /**
         * Generated implementation of the prototype, can be extended by descendant prototype implementations.
         */
        protected static class DemoWorkflowArgumentsImpl implements DemoWorkflowArguments {

            private final boolean encrypted;
            private final int workflowId;
            private final long size;
            private final Optional<String> label;

            /**
             * Create an instance providing a builder.
             *
             * @param builder extending builder base of this prototype
             */
            protected DemoWorkflowArgumentsImpl(DemoWorkflowArguments.BuilderBase<?, ?> builder) {
                this.size = builder.getSize();
                this.encrypted = builder.isEncrypted();
                this.workflowId = builder.getWorkflowId();
                this.label = builder.getLabel().map(Function.identity());
            }

            @Override
            public long getSize() {
                return size;
            }

            @Override
            public boolean isEncrypted() {
                return encrypted;
            }

            @Override
            public int getWorkflowId() {
                return workflowId;
            }

            @Override
            public Optional<String> getLabel() {
                return label;
            }

            @Override
            public String toString() {
                return "DemoWorkflowArguments{"
                        + "size=" + size + ","
                        + "encrypted=" + encrypted + ","
                        + "workflowId=" + workflowId + ","
                        + "label=" + label
                        + "}";
            }

            @Override
            public boolean equals(Object o) {
                if (o == this) {
                    return true;
                }
                if (!(o instanceof DemoWorkflowArguments other)) {
                    return false;
                }
                return size == other.getSize()
                    && encrypted == other.isEncrypted()
                    && workflowId == other.getWorkflowId()
                    && Objects.equals(label, other.getLabel());
            }

            @Override
            public int hashCode() {
                return Objects.hash(size, encrypted, workflowId, label);
            }

        }

    }

    /**
     * Fluent API builder for {@link DemoWorkflowArguments}.
     */
    class Builder extends DemoWorkflowArguments.BuilderBase<DemoWorkflowArguments.Builder, DemoWorkflowArguments> implements io.helidon.common.Builder<DemoWorkflowArguments.Builder, DemoWorkflowArguments> {

        private Builder() {
        }

        /**
         * Create an instance of the prototype. Calling this method may update this builder from providers and decorators, so repeated calls may have side effects.
         */
        @Override
        public DemoWorkflowArguments buildPrototype() {
            preBuildPrototype();
            validatePrototype();
            return new DemoWorkflowArguments.BuilderBase.DemoWorkflowArgumentsImpl(this);
        }

        /**
         * Create an instance from this builder. Calling this method may update this builder from providers and decorators, so repeated calls may have side effects.
         */
        @Override
        public DemoWorkflowArguments build() {
            return buildPrototype();
        }

    }

}
