package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import io.lettuce.core.dynamic.annotation.Command;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    //互斥锁 set方法
    public void setWithMutex(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    //逻辑过期 set方法
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
    public  <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }
        if ("".equals(json)){
            return null;
        }
        //不存在，根据id查数据库
        R r = dbFallback.apply(id);
        if (r == null){
            //数据库中也没查到，写入空值 解决缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        this.setWithMutex(key, r, time, unit);
        return r;
    }
    public  <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        String lockKey = LOCK_SHOP_KEY + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }
        if ("".equals(json)){
            return null;
        }
        //缓存未命中，根据id查数据库
        //实现缓存重建
        //获取互斥锁
        R r = null;
        try {
            boolean hasMutex = tryLock(lockKey);
            //是否获取锁成功
            //获取锁失败，则休眠
            if (!hasMutex){
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit); //递归检查是否命中缓存
            }
            //获取锁成功，再次检查Redis缓存是否命中，多个线程递归可能存在已经缓存过的情况，如果命中，直接返回数据。
//            String json2 = stringRedisTemplate.opsForValue().get(key);
//            if (StrUtil.isNotBlank(json2)){
//                return JSONUtil.toBean(json2, type);
//            }
            // 查数据库
            r = dbFallback.apply(id);
            //模拟重建延时
            Thread.sleep(200);
            if (r == null){
                //数据库中也没查到，写入空值 解决缓存穿透
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //存在，写入redis
            this.setWithMutex(key, r, time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放互斥锁
            unLock(lockKey);
        }
        return r;
    }

    //设置线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //逻辑过期解决缓存穿透
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
     String json = stringRedisTemplate.opsForValue().get(key);
     //未命中 返回空
     if (StrUtil.isBlank(json)){
         return null;
     }
     //命中
     //先把json反序列化为对象
     RedisData redisData = JSONUtil.toBean(json, RedisData.class);
     R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
     LocalDateTime expireTime = redisData.getExpireTime();
     //判断key是否过期
     //未过期，返回
     if (expireTime.isAfter(LocalDateTime.now())){
         return r;
     }
     //已过期，获取互斥锁
     String lockKey = LOCK_SHOP_KEY + id;
     boolean isLock = tryLock(lockKey);
     //获取锁失败，（返回旧数据）
     //获取锁成功，（返回旧数据），开启独立线程，重建缓存
     if (isLock){
         CACHE_REBUILD_EXECUTOR.submit(()->{
             try {
                 //查询数据库
                 R r1 = dbFallback.apply(id);
                 this.setWithLogicalExpire(key, r1, time, unit);
             } catch (Exception e) {
                 throw new RuntimeException(e);
             } finally {
                 //释放锁
                 unLock(lockKey);
             }

         });
     }
     //返回旧数据
     return r;
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


}
