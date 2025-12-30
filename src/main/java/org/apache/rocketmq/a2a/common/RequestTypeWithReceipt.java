package org.apache.rocketmq.a2a.common;

import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.core.type.TypeReference;

public class RequestTypeWithReceipt {
    private CompletableFuture<String> completableFuture;
    private TypeReference typeReference;

    public RequestTypeWithReceipt(CompletableFuture<String> completableFuture, TypeReference typeReference) {
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
