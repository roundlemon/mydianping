package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import jodd.util.StringUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FOLLOW_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        String key = FOLLOW_KEY+userId;
        if(BooleanUtil.isTrue(isFollow)){
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            boolean save = save(follow);
            if(save){
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }

        }else {
            LambdaQueryWrapper<Follow> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(Follow::getFollowUserId,followUserId).eq(Follow::getUserId,userId);
            boolean remove = remove(queryWrapper);
            if(remove){
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result followOrNot(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        LambdaQueryWrapper<Follow> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Follow::getFollowUserId,followUserId).eq(Follow::getUserId,userId);
        int count = count(queryWrapper);
        return Result.ok(count>0);
    }

    @Override
    public Result followCommons(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key1 = FOLLOW_KEY+userId;
        String key2 = FOLLOW_KEY+id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (intersect == null || intersect.isEmpty()) {
            //无交集就返回个空集合
            return Result.ok(Collections.emptyList());
        }
        List<Long> collect = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDTOList = userService.listByIds(collect).stream().map(item -> BeanUtil.copyProperties(item, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOList);
    }
}
