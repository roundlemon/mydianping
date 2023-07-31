package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;
    IVoucherOrderService proxy;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    //创建线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //利用spring提供的注解，在类初始化完毕后立即执行线程任务
    @PostConstruct
    private void init() {
        log.info("init");
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    //1.获取消息队列中的消息XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                    );
                    if (list == null || list.isEmpty()) {
                        continue;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
                } catch (Exception e) {
                    log.error("订单处理异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    //1.获取消息队列中的消息XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create("stream.orders", ReadOffset.from("0"))
                    );
                    if (list == null || list.isEmpty()) {
                        break;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
                } catch (Exception e) {
                    log.error("订单处理异常", e);
                }
            }
        }
    }


//    //创建阻塞队列  这个阻塞队列特点：当一个线程尝试从队列获取元素的时候，如果没有元素该线程阻塞，直到队列中有元素才会被唤醒获取
//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(300);//初始化阻塞队列的大小
//    //创建线程任务，内部类方式
//    private class VoucherOrderHandler implements Runnable{
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    //1. 获取队列中的订单信息
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    //2. 创建订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("订单处理异常", e);
//                }
//            }
//        }
//    }


    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long voucherId = voucherOrder.getVoucherId();
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        //获取锁
        boolean isLock = lock.tryLock(1200);
        //判断是否获取锁成功
        if (!isLock) {
            log.error("您已购买过该商品，不能重复购买");
        }
        try {
            log.info("handleVoucherOrder");
            //5.1查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            //5.2判断是否存在
            if (count > 0) {
                log.error("您已经购买过了");
            }
            //6.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")//set stock = stock -1
                    .eq("voucher_id", voucherId).gt("stock", 0) //where id = ? and stock > 0
                    .update();
            if (!success) {
                log.error("库存不足！");
            }
            this.save(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();//释放锁
        }
    }


    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), Long.toString(orderId)
        );

        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "优惠券已售罄" : "不能重复购买");
        }


        return Result.ok(orderId);

    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        Long userId = UserHolder.getUser().getId();
//
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(), userId.toString()
//        );
//
//        int r = result.intValue();
//        if (r!=0){
//            return  Result.fail(r==1?"优惠券已售罄":"不能重复购买");
//        }
//
//        VoucherOrder voucherOrder = new VoucherOrder();
//        //7.1订单id
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        //7.2用户id
//        voucherOrder.setUserId(userId);
//        //7.3代金券id
//        voucherOrder.setVoucherId(voucherId);
//        //放入阻塞对列中
//        orderTasks.add(voucherOrder);
//        log.info("orderTasks size: "+orderTasks.size());
//        //获取代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        //3.返回订单id
//        return Result.ok(orderId);
//
//    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1.查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        // 2.判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            // 尚未开始
//            return Result.fail("秒杀尚未开始!");
//        }
//        // 3.判断秒杀是否已经结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            // 尚未结束
//            return Result.fail("秒杀已结束！");
//        }
//        // 4.判断库存是否充足
//        if (voucher.getStock() < 1) {
//            // 库存不足
//            return Result.fail("库存不足!");
//        }
//        Long userId = UserHolder.getUser().getId();
//
////        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:"+userId,stringRedisTemplate);
//        RLock simpleRedisLock = redissonClient.getLock("order:" + userId);
//        boolean lock = simpleRedisLock.tryLock();
//
//        if(!lock){
//            return Result.fail("一人只能下单一次");
//        }
//
//        try {
////        synchronized (userId.toString().intern()) {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.oneOrderPrePerson(voucherId);
////        }
//        } finally {
//            simpleRedisLock.unlock();
//        }
//    }

    @Transactional
    public Result oneOrderPrePerson(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 5.1 查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.2 判断是否存在
        if (count > 0) {
            return Result.fail("用户已经购买过一次！");
        }
        // 6.扣减库存
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("库存不足");
        }
        //7. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //7.1 设置订单id
        long orderId = redisIdWorker.nextId("order");
        //7.2 设置用户id
        Long id = UserHolder.getUser().getId();
        //7.3 设置代金券id
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(id);
        //8. 将订单数据保存到表中
        save(voucherOrder);
        //9. 返回订单id
        return Result.ok(orderId);
    }

    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        log.info("createVoucherOrder");
        Long voucherId = voucherOrder.getVoucherId();
        //5.一人一单
        Long userId = voucherOrder.getId();
        //5.1查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        //5.2判断是否存在
        if (count > 0) {
            log.error("您已经购买过了");
        }
        //6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")//set stock = stock -1
                .eq("voucher_id", voucherId).gt("stock", 0) //where id = ? and stock > 0
                .update();
        if (!success) {
            log.error("库存不足！");
        }
        this.save(voucherOrder);
    }
}
