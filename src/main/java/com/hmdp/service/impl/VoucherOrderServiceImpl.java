package com.hmdp.service.impl;

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
import io.lettuce.core.RedisClient;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 * st
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService voucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 阻塞队列，用来保存订单 当一个线程尝试去从阻塞队列里去获取元素的时候，
    // 如果没有元素的话，线程就会被阻塞，直到有值可以去。 ArrayBlockingQueue 使用数组实现的阻塞队列。
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    // 线程池:异步。 获取单线程，慢慢处理即可
    private static final ExecutorService SECKILL_ORDER_EXECUTOR =
            Executors.newSingleThreadExecutor();

    /*
        @POSTConstruct 在类初始化后，马上执行。
     */
    @PostConstruct
    private void init(){
        // 提交要执行的任务。
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{
        /*
            用户一旦开始秒杀，就必须添加任务。任务在项目启动后马上就要执行。使用Spring里面的注解。异步下单：开启独立线程完成任务。
         */
        @Override
        public void run() {
            while (true){
                //1.获取队列中的订单信息,从队列中不断取出订单。
                /*
                    检索并删除此队列的头，在必要时等待，直到元素可用。
                    返回: 队列的头 抛出: InterruptedException -如果在等待时被中断
                 */
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.创建订单(异步实现！)
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                }
            }
        }
    }
    // 从线程池中取线程来查看阻塞队列中是否任务需要执行

    // 将代理对象放到全局的成员变量里面。
    private IVoucherOrderService proxy;
    /**
     * 创建订单
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 因为这里是新的线程，不再是主线程去执行任务了。ThreadLocal是线程隔离的，不能再获取到userId。
        // 1.获取用户
        Long userId = voucherOrder.getUserId();
        // 2.创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 3.尝试去获取锁
        boolean isLock = lock.tryLock();
        // 4.判断是否获取锁成功
        if(!isLock){
            // 获取锁失败，返回错误或者重试
            log.error("不允许重复下单");
            return;
        }
        /*
            AopContext.currentProxy();
            观察底层的源码，发现 private static final ThreadLocal<Object> currentProxy =
                new NamedThreadLocal<>("Current AOP proxy");

                ThreadLocal也是隔离线程的，不能再这样获取代理对象了。
                作为子线程(而不是父线程，是无法从ThreadLocal中取出父线程拥有的东西的。)

                如果我们想要proxy代理对象，只能在主线程里面获取。
         */
        try {
             proxy.createVoucherOrder(voucherOrder);
        }finally {
            // 释放锁
            lock.unlock();
        }
    }
    /**
     * 使用Lua脚本实现秒杀的优化，开启另外一个线程(后厨)来进行数据库的工作。  主线程，
     * 任何人进入创建订单的逻辑：首先进入
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1.执行 lua脚本:判断用户是否具有购买资格！ execute(xxx) 执行的脚本以及脚本中对应位置的参数。
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        // 将Long型转换为int型。
        int r = result.intValue();
        // 2.判断结果是否为0
        if (r != 0) {
            // 2.1.不为0 ，代表没有购买资格 1：库存不足  2：不能重复下单
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // TODO 将订单id(后厨墙上面贴着的顾客要做的饭)保存到阻塞队列
        //2.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //2.1 订单id
        voucherOrder.setId(orderId);
        //2.2 用户id
        voucherOrder.setUserId(userId);
        //2.3代金券id
        voucherOrder.setVoucherId(voucherId);
        // 将任务添加到阻塞队列里面。
        orderTasks.add(voucherOrder);

        // 获取代理对象,并且传递，让后续线程也可以获取到。将其放到成员变量里面。 在这里进行赋值。
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 3.返回订单id
        return Result.ok(orderId);
    }





//    由于有订单表和优惠券库存表，两张表的更新，最好加上事务。

    /**
     * 1.扣减优惠券的内存
     * 2.将用户抢到优惠券的信息写入到订单，然后创建订单。
     * @param voucherId 当前用户正在抢购的优惠券的id。
     * @return
     * @throws InterruptedException
     */
//    @Override
//    public Result seckillVoucher(Long voucherId) throws InterruptedException {
//        //1.查询优惠券，访问数据库
//        SeckillVoucher voucher = voucherService.getById(voucherId);
//        //2.判断秒杀是否已经开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始!");
//        }
//        //3.判断秒杀是否已经结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束!");
//        }
//        //4.判断库存是否充足
//        if (voucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//        // 获取当前登录用户的id
//        Long userId = UserHolder.getUser().getId();
//
////        synchronized (userId.toString().intern()) {
//        /*
//            实现一个用户只能下一单的功能，对UserID进行synchronized操作。
//         */
//        // 使用RedissonClient的锁，获取分布式锁
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//
//
//        // 创建自己设计的锁的对象
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        // 获取锁, 如果不填任何参数的话，请求失败后，会立刻失败，不再发送请求。
//        // 尝试获取锁，参数分别是:获取锁的最大等待时间（期间会重试），锁自动释放时间，时间单位
//        boolean isLock = lock.tryLock(1L, TimeUnit.SECONDS);
//        /*
//            return this.evalWriteAsync(this.getName(), LongCodec.INSTANCE, command,
//            "if (redis.call('exists', KEYS[1]) == 0)
//            then redis.call('hincrby', KEYS[1], ARGV[2], 1);
//            redis.call('pexpire', KEYS[1], ARGV[1]);
//            return nil; end; if (redis.call('hexists', KEYS[1], ARGV[2]) == 1)
//            then redis.call('hincrby', KEYS[1], ARGV[2], 1);
//            redis.call('pexpire', KEYS[1], ARGV[1]);
//            return nil; end; return redis.call('pttl', KEYS[1]);",
//            Collections.singletonList(this.getName()), this.internalLockLeaseTime, this.getLockName(threadId));
//         */
//        //判断是否获取锁成功
//        //在获取锁的时候存入线程的标识，在释放锁的时候要进行判断，防止进行锁的误删
//        if (!isLock) {
//            // 获取锁失败, 返回错误或重试
//            // 这个锁是防止一人多单的锁，乐观锁解决超卖
//            return Result.fail("不允许重复下单！");
//        }
//        try {
//            //拿到事务的代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.create(voucherId);
//        } finally {
//            lock.unlock();
//        }
//    }

    /**
     *  实现一人一单,前面的同步处理
     *
     * @param voucherId
     * @return
     */
    @Transactional
//    synchronized 放在类上，锁的是this，也就意味着，不管是任何一个用户来了，都要加这个锁。而且是同一把锁，性能会降低。
    public Result create(Long voucherId) {

        /**
         * 乐观锁的前提是修改数据，所以这里只能用悲观锁。
         * 同一个id加一把锁，不同的id，加不同的锁，减少锁的范围，提高性能。
         * 字符串对象在方法区只存在一份，拿来当锁的同步监视器。
         * 因为即使是同一个id,在不同线程中调用toString得到的是不同的字符串对象。所以要使用intern()方法。
         */
        //5.扣减库存
//        通过乐观锁进行实现，加上一个版本号
        Long userId = UserHolder.getUser().getId();
        // 扣库存
        boolean success = voucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0).update();
        if (!success) {
            return Result.fail("库存不足");
        }
        //6. 一人一单
        //6.1 查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        //6.2判断是否存在
        if (count > 0) {
            //用户已经购买过了。
            return Result.fail("用户已经购买过一次!");
        }
        //6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //6.1 订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //6.2 用户id
        voucherOrder.setUserId(userId);
        //6.3代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        //7.返回订单id
        return Result.ok(orderId);
    }

    /*
            进行数据库的修改和订单创建的异步处理，是不再需要返回给前端任何数值的。只需要进行业务的处理即可。
     */
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 实现一人一单
        Long userId = voucherOrder.getUserId();

        // 查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 判断订单是否存在
        if (count > 0){
            //用户已经购买过了！
            log.error("用户已经购买过一次了！");
            return;
        }

        // 该用户前面并没有进行购买过，进行库存的扣减
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success){
            //扣减库存失败
            log.error("扣减库存失败");
            return;
        }

        //创建订单
        save(voucherOrder);


    }
}
