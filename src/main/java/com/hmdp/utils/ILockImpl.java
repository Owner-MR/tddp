package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class ILockImpl implements ILock {

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public ILockImpl(StringRedisTemplate stringRedisTemplate, String name) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unLock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    @Override
    public boolean tryLock(long timeoutSec) {
        String key = KEY_PREFIX + name;
        //获取
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, threadId, timeoutSec, TimeUnit.SECONDS);
        //防止success为null，拆箱时空指针异常
        return Boolean.TRUE.equals(success);
    }
    @Override
    public void unLock(){
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()
                );
    }
    /*@Override
    public void unLock() {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        String lockThreadId = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        if (lockThreadId != null && lockThreadId.equals(threadId)){
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }*/
}
