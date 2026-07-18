package com.hmdp.importer;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.cache.ShopBloomFilter;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 这一整个包，作用专门做高德地图 POI 商户数据的导入功能，用来把高德接口的商铺数据，读取、转换、最终插入到你的店铺数据库里。
 * 本类是数据导入的调度主类。
 * 依赖GaodeApiClient，负责整套业务流程：遍历城市各个坐标中心点、循环分页拉取 POI、把高德的 POI 对象转换成自己项目的 Shop 门店实体、调用 Mapper 写入数据库。
 * 是这一套导入功能的入口。
 */

@Component
public class GaodePoiImporter {
    private static final List<Location> CENTERS = Arrays.asList(
            new Location("钟楼", 108.946465, 34.263206),
            new Location("小寨", 108.9479, 34.2256),
            new Location("高新", 108.8938, 34.2294),
            new Location("长安大学城", 108.867065, 34.239599),
            new Location("大唐不夜城", 108.963852, 34.212536)
    );

    // 只声明依赖，不手动new
    private final GaodeApiClient apiClient;
    private final ShopMapper shopMapper;
    private final ShopBloomFilter shopBloomFilter;

    public GaodePoiImporter(
            GaodeApiClient apiClient,
            ShopMapper shopMapper,
            ShopBloomFilter shopBloomFilter) {
        this.apiClient = apiClient;
        this.shopMapper = shopMapper;
        this.shopBloomFilter = shopBloomFilter;
    }

    public void importByType(String gaodeTypeCode, Long shopTypeId) {
        for (Location center : CENTERS) {
            String location =
                    center.getLongitude() + "," + center.getLatitude();

            for (int page = 1; ; page++) {
                GaodeResponse response =
                        apiClient.search(location, gaodeTypeCode, page);

                if (response == null || response.getPois() == null || response.getPois().isEmpty()) {
                    break;
                }

                System.out.printf(
                        "%s 第%d页：获取到%d条POI%n",
                        center.getName(),
                        page,
                        response.getPois().size()
                );

                // 分页循环内的入库逻辑
                List<Poi> poiList = response.getPois();
                for (Poi poi : poiList) {
                    if (poi.getId() == null
                            || poi.getName() == null
                            || poi.getLocation() == null) {
                        continue;
                    }

                    // 转换
                    Shop shop = toShop(
                            poi,
                            shopTypeId,
                            center.getName()
                    );
                    // 查重更新
                    Shop old = shopMapper.selectOne(
                            new QueryWrapper<Shop>().eq("poi_id", poi.getId()));

                    if (old == null) {
                        shopMapper.insert(shop);
                    } else {
                        shop.setId(old.getId());
                        shopMapper.updateById(shop);
                    }

                    // POI 导入直接使用 Mapper，绕过了 ShopService，因此这里必须同步新商户 ID。
                    // add 是幂等操作，更新已有商户时重复添加同一个 ID 不会造成业务问题。
                    shopBloomFilter.add(shop.getId());
                }
            }
        }
    }

    // POI转Shop转换方法
    private Shop toShop(
            Poi poi,
            Long shopTypeId,
            String area
    ) {
        Shop shop = new Shop();
        shop.setPoiId(poi.getId());
        shop.setName(poi.getName());
        shop.setTypeId(shopTypeId);

        // 拆分经纬度，先默认经纬度0
        shop.setX(0.0);
        shop.setY(0.0);
        if (poi.getLocation() != null && poi.getLocation().contains(",")) {
            String[] loc = poi.getLocation().split(",");
            shop.setX(Double.valueOf(loc[0]));
            shop.setY(Double.valueOf(loc[1]));
        }
        // 拼接图片url
        if (poi.getPhotos() != null) {

            String images = poi.getPhotos()
                    .stream()
                    .map(Photo::getUrl)
                    .collect(Collectors.joining(","));

            shop.setImages(images);
        } else {
            shop.setImages(""); // 无图片兜底空串
        }
        // 评分放大10倍，默认评分450
        shop.setScore(450);
        // 有高德真实评分再覆盖
        if (poi.getRating() != null && !poi.getRating().isEmpty()) {
            double rating = Double.parseDouble(poi.getRating());
            shop.setScore((int) (rating * 10));
        }
        // 基础字段
        shop.setSold(0); // 销量初始为0
        shop.setCity("西安市"); // 城市默认为西安市
        shop.setComments(0); // 评论数初始0
        shop.setOpenHours("11:00-13:50,17:00-20:50"); // 营业时间默认设定
        shop.setAddress(poi.getAddress());
        shop.setArea(area);
        shop.setPhone(convertTel(poi.getTel()));
        return shop;
    }

    private String convertTel(Object tel) {
        if (tel == null) {
            return null;
        }
        if (tel instanceof String) {
            return (String) tel;
        }
        if (tel instanceof List) {
            List<?> list = (List<?>) tel;
            return list.stream()
                    .map(Object::toString)
                    .collect(Collectors.joining(","));
        }
        return tel.toString();
    }
}
