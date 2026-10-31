package com.dsh.whalepet;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 统一的后台线程池：避免散落的 new Thread 无上限增长。 */
public final class Bg {

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "pet-bg");
        t.setDaemon(true);
        return t;
    });

    private Bg() {
    }

    public static void run(Runnable r) {
        try {
            POOL.execute(r);
        } catch (Throwable ignored) {
        }
    }
}
