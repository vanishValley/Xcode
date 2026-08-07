package com.xu.http;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * 让 OkHttp 请求响应 Java 工作线程的中断信号。
 *
 * <p>Multi-Agent 任务通常通过 {@code Future.cancel(true)} 中断工作线程，
 * 但线程中断不会自动取消已经发出的 OkHttp 请求。本类只负责把两种取消机制
 * 接起来：等待线程被中断时调用 {@link Call#cancel()}，避免任务已经结束而网络
 * 请求仍然占用连接。请求构造、认证、重定向和响应解析仍由各业务模块负责。</p>
 */
public final class OkHttpCallExecutor {

    private OkHttpCallExecutor() {
    }

    /**
     * 异步提交 OkHttp Call，并在当前线程中可中断地等待转换后的响应。
     *
     * @param call 已构造完成的 OkHttp 请求
     * @param responseMapper 业务侧响应转换器，在 Response 关闭前执行
     */
    public static <T> T executeInterruptibly(
            Call call,
            ResponseMapper<T> responseMapper) throws Exception {
        CompletableFuture<T> responseFuture = new CompletableFuture<>();
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call ignored, IOException error) {
                responseFuture.completeExceptionally(error);
            }

            @Override
            public void onResponse(Call ignored, Response response) {
                try (response) {
                    responseFuture.complete(responseMapper.read(response));
                } catch (Throwable error) {
                    responseFuture.completeExceptionally(error);
                }
            }
        });

        try {
            return responseFuture.get();
        } catch (InterruptedException interrupted) {
            // 中断只作用于等待线程，因此必须显式取消底层 socket 请求。
            call.cancel();
            Thread.currentThread().interrupt();
            InterruptedIOException cancelled =
                    new InterruptedIOException("HTTP request cancelled");
            cancelled.initCause(interrupted);
            throw cancelled;
        } catch (ExecutionException failed) {
            rethrow(failed.getCause());
            throw new AssertionError("unreachable");
        }
    }

    private static void rethrow(Throwable cause) throws Exception {
        if (cause instanceof IOException io) throw io;
        if (cause instanceof RuntimeException runtime) throw runtime;
        if (cause instanceof Exception exception) throw exception;
        if (cause instanceof Error error) throw error;
        throw new IOException(cause);
    }

    @FunctionalInterface
    public interface ResponseMapper<T> {
        T read(Response response) throws Exception;
    }
}
