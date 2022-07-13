package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    private IVoucherOrderService proxy;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";

        @Override
        public void run() {
            while (true) {
                try {
                    //1.获取redis消息队列中的订单信息 XREADGROUP group g1 c1 count 1 block 2000 stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2.判断是否获取成功
                    if (list == null || list.isEmpty()) {
                        //获取失败，再来一次
                        continue;
                    }
                    //3.解析消息的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //3.创建订单存到数据库
                    handleVoucherOrder(voucherOrder);
                    //4.ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常");
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    //1.获取pendingList中的订单信息 XREADGROUP group g1 c1 count 1 stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //2.判断是否获取成功
                    if (list == null || list.isEmpty()) {
                        //获取失败，pendList没有信息结束循环
                        break;
                    }
                    //3.解析消息的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //3.创建订单存到数据库
                    handleVoucherOrder(voucherOrder);
                    //4.ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pendList异常");
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
    }
    /*
    *
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(10224 * 1024);
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    //1.获取队列中的信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.创建订单存到数据库
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常");
                }
            }
        }
    }
    */

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.1获取用户id
        Long userId = voucherOrder.getUserId();
        //自己定义的SimpleRedisLock锁类
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        //使用的redisson获取的锁类
        //1.2创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //1.3获取锁
        boolean isLock = lock.tryLock();
        //1.4判断获取锁是否成功
        if (!isLock) {
            //获取锁失败，返回错误
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //3.释放锁
            lock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.1获取用户id
        Long userId = UserHolder.getUser().getId();
        //获取订单id
        long orderId = redisIdWorker.nextId("order");
        //1.2执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        //2.判断执行结果是否为0
        int r = result.intValue();
        if (r != 0) {
            //2.1不为0，没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //4.2获取代理对象（确保事务Transactional生效，保证createVoucherOrder 提交完事务之后再释放锁）
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //5返回订单id
        return Result.ok(orderId);
    }

    /*
    *
    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.1获取用户id
        Long userId = UserHolder.getUser().getId();
        //1.2执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        //2.判断执行结果是否为0
        int r = result.intValue();
        if (r != 0) {
            //2.1不为0，没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //3--4为0，有购买资格，把下单信息保存到阻塞队列中
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
        //4.1放入阻塞队列
        orderTasks.add(voucherOrder);
        //4.2获取代理对象（确保事务Transactional生效，保证createVoucherOrder 提交完事务之后再释放锁）
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //5返回订单id
        return Result.ok(orderId);
    }
    */

    /*
    *
    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠卷
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            //尚未开始
            return Result.fail("秒杀尚未开始");
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
        //6.1创建锁对象
        //自己定义的SimpleRedisLock锁类
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        //使用的redisson获取的锁类
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //6.2获取锁
        boolean isLock = lock.tryLock();
        //6.3判断获取锁是否成功
        if (!isLock) {
            //获取锁失败，返回错误
            return Result.fail("一个用户只能下一单");
        }
        try {
            //7获取代理对象（确保事务Transactional生效，保证createVoucherOrder 提交完事务之后再释放锁）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            //8.释放锁
            lock.unlock();
        }
    }

     */

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //1.一人一单判断
        Long userId = voucherOrder.getUserId();
        //1.1查询订单
        Integer count = query().eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId())
                .count();
        //1.2判断是否存在
        if (count > 0) {
            log.error("用户已经购买过一次");
            return;
        }
        //2.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") //set stock = stock-1
                .eq("voucher_id", voucherOrder.getVoucherId()) //where voucher_id==voucherId
                .gt("stock", 0) //where stock >0
                .update();
        if (!success) {
            //扣减失败
            log.error("库存不足！");
            return;
        }
        save(voucherOrder);
    }
}