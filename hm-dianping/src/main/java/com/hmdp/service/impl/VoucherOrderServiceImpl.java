package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠卷
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            //尚未开始
            return Result.fail("秒杀尚未开v始");
        }
        //3.判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            //已经结束
            return Result.fail("秒杀已经结束");
        }
        //4.判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
        //5.查询用户id
        Long userId = UserHolder.getUser().getId();
        //6.对相同用户id的操作加锁，防止并行执行，一人多单
        synchronized (userId.toString().intern()) {
            //6.1获取代理对象（确保事务Transactional生效，保证createVoucherOrder 提交完事务之后再释放锁）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //1.一人一单判断
        Long userId = UserHolder.getUser().getId();
        //1.1查询订单
        Integer count = query().eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .count();
        //1.2判断是否存在
        if (count > 0) {
            return Result.fail("用户已经买过一次了");
        }
        //2.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") //set stock = stock-1
                .eq("voucher_id", voucherId) //where voucher_id==voucherId
                .gt("stock", 0) //where stock >0
                .update();
        if (!success) {
            return Result.fail("库存不足");
        }
        //3.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //3.1订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //3.2保存用户id
        voucherOrder.setUserId(userId);
        //3.3优惠券id
        voucherOrder.setVoucherId(voucherId);
        //4.保存订单信息
        save(voucherOrder);
        //5.返回订单id
        return Result.ok(orderId);

    }
}