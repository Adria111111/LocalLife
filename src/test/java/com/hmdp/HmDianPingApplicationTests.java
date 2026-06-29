package com.hmdp;

import com.hmdp.utils.RedisIdWorker;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RunWith(SpringRunner.class)
@SpringBootTest
public class HmDianPingApplicationTests {

    @Resource
    private RedisIdWorker redisIdWorker;
    private ExecutorService executorService = Executors.newFixedThreadPool(500); // 快速创建固定大小线程池

    // 开 300 个线程，每个线程循环拿 100 个订单 ID，一共生成 300*100=30000 个 ID，最后统计全部跑完花了多少毫秒
    /* executorService：线程池，提前创建好一批线程，用来执行任务，不用手动 new Thread ()
       CountDownLatch：线程等待工具，俗称 “闭锁”，用来让主线程等所有子线程全部干完活再算时间
       Runnable：线程要执行的任务体，里面写业务逻辑（循环获取 ID）*/
    @Test
    public void testIdWorker() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            countDownLatch.countDown(); // 这个线程所有活干完了，计数器减1
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            executorService.submit(task);
        }
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println("耗时：" + (end - begin));
    }
}



