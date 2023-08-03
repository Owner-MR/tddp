package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import jodd.util.StringUtil;
import net.sf.jsqlparser.expression.DateTimeLiteralExpression;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Key;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private CacheClient cacheClient;
    @Autowired
    private IShopService shopService;
    @Override
    public Result queryByID(Long id) {
        //从redis中查询缓存是否存在
        //解决缓存穿透
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //互斥锁解决缓存穿透、缓存击穿
        //Shop shop = cacheClient.queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //逻辑过期解决缓存穿透、缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id , Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null){
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }
    /*private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public Shop queryWithLogicalExpire(Long id){
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //未命中 返回空
        if (StrUtil.isBlank(shopJson)){
            return null;
        }
        //命中
        //先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断key是否过期
        //未过期，返回
        if (expireTime.isAfter(LocalDateTime.now())){
            return shop;
        }
        //已过期，获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //获取锁失败，（返回旧数据）
        //获取锁成功，（返回旧数据），开启独立线程，重建缓存
        if (isLock){
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    this.saveData2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }

            });
        }
        //返回旧数据
        return shop;
    }
    *//*public Shop queryWithPassThrough(Long id){
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        if (StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if ("".equals(shopJson)){
            return null;
        }
        //不存在，根据id查数据库
        Shop shop = getById(id);
        if (shop == null){
            //数据库中也没查到，写入空值 解决缓存穿透
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }*//*
    private Shop queryWithMutex(Long id){
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        String key = LOCK_SHOP_KEY + id;
        if (StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if ("".equals(shopJson)){
            return null;
        }
        //缓存未命中，根据id查数据库
        //实现缓存重建
        //获取互斥锁
        Shop shop = null;
        try {
            boolean hasMutex = tryLock(key);
            //是否获取锁成功
            //获取锁失败，则休眠
            if (!hasMutex){
                Thread.sleep(50);
                return queryWithMutex(id); //递归检查是否命中缓存
            }
            //获取锁成功，再次检查Redis缓存是否命中，多个线程递归可能存在已经缓存过的情况，如果命中，直接返回数据。
//            String shopJson2 = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//            if (StrUtil.isNotBlank(shopJson2)){
//                return JSONUtil.toBean(shopJson2, Shop.class);
//            }
            // 查数据库，写入缓存
            shop = getById(id);
            //模拟重建延时
            Thread.sleep(200);
            if (shop == null){
                //数据库中也没查到，写入空值 解决缓存穿透
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放互斥锁
            unLock(key);
        }
        return shop;
    }
    //获得互斥锁
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }*/
    //释放锁
    /*private void unLock(String key){
        stringRedisTemplate.delete(key);
    }*/
   /* public void saveData2Redis(Long id, Long expireSeconds) throws InterruptedException {
        //查数据
        Shop shop = getById(id);
        Thread.sleep(200);
        //封装redisdata
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        //设置过期时间
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }*/
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) return Result.fail("店铺id不能为空");
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    @Override
    public Result queryShopById(Integer typeId, Integer current, Double x, Double y) {
        //判断是否需要根据坐标查询
        if (x == null || y == null){
            // 根据类型分页查询
            Page<Shop> page = shopService.query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        String key = SHOP_GEO_KEY + typeId;
        //从redis获取商铺数据
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
        );
        if (results == null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from)
            //下一页没有了 返回
            return Result.ok(Collections.emptyList());
        HashMap<String, Distance> distanceHashMap = new HashMap<>(list.size());
        ArrayList<String> ids = new ArrayList<>();
        //遍历redis查询结果，取出id 和 distance
        for (GeoResult<RedisGeoCommands.GeoLocation<String>> geoResult : list) {
            String id = geoResult.getContent().getName();
            ids.add(id);
            Distance distance = geoResult.getDistance();
            distanceHashMap.put(id, distance);
        }
        String idStr = StrUtil.join(",", ids);
        //根据id顺序 查询附近的商铺
        List<Shop> shopList = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shopList) {
            shop.setDistance(distanceHashMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shopList);
    }

    public void saveShop2Redis(Long id, Long exprireSeconds){
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(exprireSeconds));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
}
