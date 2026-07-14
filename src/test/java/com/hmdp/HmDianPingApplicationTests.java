package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.importer.GaodePoiImporter;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisIdWorker;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import com.hmdp.utils.ShopGeoUtil;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@RunWith(SpringRunner.class)
@SpringBootTest
public class HmDianPingApplicationTests {

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private IShopService shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private ExecutorService executorService = Executors.newFixedThreadPool(500);

    @Resource
    private GaodePoiImporter importer;

    @Resource
    private ShopGeoUtil shopGeoUtil;

    @Test
    public void loadShopGeoData() {
        List<Shop> shops = shopService.list();
        Map<Long, List<Shop>> shopMap = shops.stream()
                .collect(Collectors.groupingBy(Shop::getTypeId));

        for (Map.Entry<Long, List<Shop>> entry : shopMap.entrySet()) {
            String key = SHOP_GEO_KEY + entry.getKey();
            List<RedisGeoCommands.GeoLocation<String>> locations = entry.getValue().stream()
                    .filter(shop -> shop.getX() != null && shop.getY() != null)
                    .map(shop -> new RedisGeoCommands.GeoLocation<>(
                            shop.getId().toString(),
                            new Point(shop.getX(), shop.getY())
                    ))
                    .collect(Collectors.toList());
            if (!locations.isEmpty()) {
                stringRedisTemplate.opsForGeo().add(key, locations);
            }
        }
    }

    @Test
    public void testIdWorker() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            countDownLatch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            executorService.submit(task);
        }
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println("耗时：" + (end - begin));
    }

    @Test
    public void importTest() {
        importer.importByType(
                "050000",
                1L
        );
    }

    @Test
    public void loadShopGeoOnly() {
        // 只执行GEO导入，跳过importer导入数据库
        // 美食typeId=1，其他分类改数字即可
        shopGeoUtil.loadShopGeoByTypeId(1L);
        System.out.println("美食店铺坐标已加载到Redis GEO");
    }
}


