package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.hmdp.utils.SystemConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryShopType() {
//        String shopType = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);
//        List<String> shopType = stringRedisTemplate.opsForList().range(CACHE_SHOP_TYPE_KEY, 0, -1);
        Set<String> shopType = stringRedisTemplate.opsForZSet().range(CACHE_SHOP_TYPE_KEY, 0, -1);

        if (shopType != null && shopType.size()>0) {
            List<ShopType> shopTypes = new ArrayList<>();
            for (String item : shopType) {
                ShopType shopTypeOne = JSONUtil.toBean(item, ShopType.class);
                shopTypes.add(shopTypeOne);
            }
            return Result.ok(shopTypes);
        }

        LambdaQueryWrapper<ShopType> queryWrapper = new LambdaQueryWrapper<>();

        queryWrapper.orderByAsc(ShopType::getSort);
        List<ShopType> shopTypeList = list(queryWrapper);
        if (shopTypeList == null) {
            return Result.fail("分类不存在");
        }
//        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY,JSONUtil.toJsonStr(shopTypeList));
        for (ShopType type : shopTypeList) {
            String typeJson = JSONUtil.toJsonStr(type);
            stringRedisTemplate.opsForZSet().add(CACHE_SHOP_TYPE_KEY,typeJson,type.getSort());
        }
        return Result.ok(shopTypeList);
    }
}
