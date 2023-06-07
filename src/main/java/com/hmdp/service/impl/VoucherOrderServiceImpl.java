package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.ILock;
import com.hmdp.utils.ILockImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override

    public Result seckillVourcher(Long voucherId) {
        //查询优惠券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher == null) return Result.fail("无可用优惠券");
        //判断秒杀是否开始
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀活动未开始");
        }
        //判断是否已经结束
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀活动已经结束");
        }
        //判断库存是否充足
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();
        //获取锁对象
        ILockImpl lock = new ILockImpl(stringRedisTemplate, "order:" + userId);
        //获取锁
        boolean getLock = lock.tryLock(1200);
        //获取锁失败,直接返回
        if (!getLock) {
            return Result.fail("不允许重复下单");
        }
        //获取代理对象（事务）
        IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
        try {
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unLock();
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //一人一单
        Long userId = UserHolder.getUser().getId();

        //查询订单是否存在
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        //存在，返回异常
        if (count > 0) return Result.fail("该用户已购买过此优惠券！");
        //不存在
        //扣减库存
        //update seckillVoucher set stock = stock - 1 where voucher_id = voucherId;
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1").gt("stock", 0)
                .eq("voucher_id", voucherId).update();
        //创建订单id
        if (!success) {
            return Result.fail("扣减失败！");
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        //保存到数据库
        save(voucherOrder);
        //返回订单
        return Result.ok(orderId);

    }
}
