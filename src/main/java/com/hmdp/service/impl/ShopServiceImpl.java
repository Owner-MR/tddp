package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Key;
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
    @Override
    public Result queryByID(Long id) {
        //从redis中查询缓存是否存在
        //解决缓存穿透
        Shop shop = queryWithPassThrough(id);
        //互斥锁解决缓存击穿


        return Result.ok(shop);
    }
    public Shop queryWithPassThrough(Long id){
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
    }
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
    }
    //释放锁
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }
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
}
