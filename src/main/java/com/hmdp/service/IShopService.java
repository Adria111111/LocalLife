package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.vo.ShopVO;

import java.util.List;

/**
 * <p>
 * 服务类
 * </p>
 */
public interface IShopService extends IService<Shop> {

    Result queryById(Long id);

    /**
     * 根据类型查询店铺
     * @param typeId 类型id
     * @param current 页码
     * @param x x坐标
     * @param y y坐标
     * @return 店铺列表
     */
    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);

    Result update(Shop shop);
}
