package com.xu.tool.impl;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/** Makes an OkHttp call obey worker-thread interruption. */
final class InterruptibleHttp {

    private InterruptibleHttp() {
    }

    static <T> T execute(
            Call call,
            ResponseHandler<T> responseHandler) throws Exception {
        CompletableFuture<T> responseFuture =
                new CompletableFuture<>();
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call ignored, IOException error) {
                responseFuture.completeExceptionally(error);
            }

            @Override
            public void onResponse(Call ignored, Response response) {
                try (response) {
                    responseFuture.complete(
                            responseHandler.read(response));
                } catch (Throwable error) {
                    responseFuture.completeExceptionally(error);
                }
            }
        });

        try {
            return responseFuture.get();
        } catch (InterruptedException interrupted) {
            call.cancel();
            Thread.currentThread().interrupt();
            InterruptedIOException cancelled =
                    new InterruptedIOException("HTTP request cancelled");
            cancelled.initCause(interrupted);
            throw cancelled;
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException(cause);
        }
    }

    @FunctionalInterface
    interface ResponseHandler<T> {
        T read(Response response) throws Exception;
    }
}
