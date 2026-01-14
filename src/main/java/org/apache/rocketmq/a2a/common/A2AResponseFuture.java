package org.apache.rocketmq.a2a.common;

import java.util.concurrent.CompletableFuture;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * Used to handle the response results of pending outgoing requests,
 * where the responses are asynchronously obtained and deserialized by a message listener
 */
public class A2AResponseFuture {

    /**
     * Asynchronously passing results across threads
     */
    private CompletableFuture<String> completableFuture;

    /**
     * Generic type reference used for deserializing A2A protocol response results
     */
    private TypeReference typeReference;

    /**
     * Creates an AsyncTypedResult with the given future and type reference
     * @param completableFuture Asynchronously passing results across threads
     * @param typeReference TypeReference used to deserialize the response into the correct generic type
     */
    public A2AResponseFuture(CompletableFuture<String> completableFuture, TypeReference typeReference) {
        this.completableFuture = completableFuture;
        this.typeReference = typeReference;
    }

    public CompletableFuture<String> getCompletableFuture() {
        return completableFuture;
    }

    public TypeReference getTypeReference() {
        return typeReference;
    }
}
