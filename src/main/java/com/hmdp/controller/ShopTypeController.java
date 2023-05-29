package com.hmdp.controller;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IShopTypeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @GetMapping("list")
    public Result queryTypeList() {
        String shopTypeJson = stringRedisTemplate.opsForValue().get("shop:typeList");
        if (StrUtil.isNotBlank(shopTypeJson)){
            //ShopType shopType = BeanUtil.toBean(shopTypeJson, ShopType.class);
            List<ShopType> typeList = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(typeList);
        }
        List<ShopType> typeList = typeService
                .query().orderByAsc("sort").list();
        if (typeList == null){
            return Result.fail("商铺信息不存在");
        }
        stringRedisTemplate.opsForValue().set("shop:typeList", JSONUtil.toJsonStr(typeList));
        return Result.ok(typeList);
    }
}
