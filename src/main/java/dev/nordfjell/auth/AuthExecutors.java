package dev.nordfjell.auth;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class AuthExecutors {
    private AuthExecutors() { }

    static ThreadPoolExecutor database(int capacity) {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(capacity), runnable -> {
                Thread thread = new Thread(runnable, "NordAuth-Database");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    }
}
