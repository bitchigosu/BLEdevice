package com.example.bledevice;

import java.util.concurrent.*;


public class Sample {

    private static int mCount = 0;
    private static final int mFloor = 100;
    private static final int mThread = 10;

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(mThread);
        Future<Integer> future = executorService.submit(new Callable<Integer>() {
            @Override
            public Integer call() {
                for (int k = 0; k < mFloor; k++) {
                    mCount++;
                }
                return mCount;
            }
        });
        Integer result = future.get();
        executorService.shutdown();
        System.out.println(result);
    }

}
