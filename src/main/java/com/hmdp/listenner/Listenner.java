package com.hmdp.listenner;

import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisLock;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(topic = "asyn-Topic2",
        consumerGroup = "asyn-2-group",
        consumeMode = ConsumeMode.CONCURRENTLY,
        consumeThreadMax = 10)
public class Listenner implements RocketMQListener<MessageExt> {
    @Autowired
    private IVoucherOrderService voucherOrderService;

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void onMessage(MessageExt messageExt) {
        String msg = new String(messageExt.getBody());
        Long orderId = Long.parseLong(msg.split("-")[0]);
        Long userId = Long.parseLong(msg.split("-")[1]);
        Long voucherId = Long.parseLong(msg.split("-")[2]);

        RedisLock lock = new RedisLock("seckill:distribution:"+Long.toString(voucherId),stringRedisTemplate);

        int threadTime = 0;
        while (threadTime<100000) {
            boolean isLock = lock.tryLock(10);
            if(isLock){
                try {
                    voucherOrderService.dealTask(orderId, userId, voucherId);
                    return;
                } finally {
                    lock.unLock();
                }
            }else{
                threadTime+=200;
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

    }
}
