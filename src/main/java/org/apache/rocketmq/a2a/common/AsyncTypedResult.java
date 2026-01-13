package org.apache.rocketmq.a2a.common;

import java.util.concurrent.CompletableFuture;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * Used to handle the response results of pending outgoing requests,
 * where the responses are asynchronously obtained and deserialized by a message listener
 */
public class AsyncTypedResult {

    //Asynchronously passing computation results across threads
    private CompletableFuture<String> completableFuture;
    //Generic type reference used for deserializing A2A protocol response results
    private TypeReference typeReference;

    /**
     * Create AsyncTypedResult
     * @param completableFuture Asynchronously passing computation results across threads
     * @param typeReference Generic type reference used for deserializing A2A protocol response results
     */
    public AsyncTypedResult(CompletableFuture<String> completableFuture, TypeReference typeReference) {
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
