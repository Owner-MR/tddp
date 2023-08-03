package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

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
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        boolean invalid = RegexUtils.isPhoneInvalid(phone);
        if (invalid) {
            return Result.fail("手机号格式错误！");
        }
        String code = RandomUtil.randomNumbers(6);
        //保存验证码到redis中 设置有效期
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //发送验证码
        log.debug("发送验证码：{}", code);
        return Result.ok();
    }

    @Override
    public Result logIn(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail("手机号格式错误！");
        }
        //从redis获取验证码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (!code.equals(cacheCode)) {
            return Result.fail("验证码错误！");
        }
        //select * frome tb_user where phone = ?
        User user = query().eq("phone", loginForm.getPhone()).one();
        if (user == null) {
            //注册
            user = createUserWithPhone(loginForm.getPhone());
        }
        //用户信息保存至redis
        //随机生成token
        String token = UUID.randomUUID().toString(true);
        //user对象转hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create()
                .setIgnoreNullValue(true)
                .setFieldValueEditor((key, val) -> val.toString()));
        //存储
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        //设置有效期，及时清除用户
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);
        //返回token

        //session.setAttribute(LOGIN_USER_KEY, userDTO) ;
        return Result.ok(token);
    }

    @Override
    public void logOut(HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        log.info("token：{}", LOGIN_USER_KEY + token);
        stringRedisTemplate.delete(LOGIN_USER_KEY + token);
    }

    @Override
    public Result userByid(Long id) {
        User user = getById(id);
        if (user == null) return Result.ok();
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        return Result.ok(userDTO);
    }

    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime date = LocalDateTime.now();
        String key = USER_SIGN_KEY + userId + ":" + date.format(DateTimeFormatter.ofPattern("yyyy/MM"));
        int dayOfMonth = date.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime date = LocalDateTime.now();
        int dayOfMonth = date.getDayOfMonth() + 7;
        String key = USER_SIGN_KEY + userId + ":" + date.format(DateTimeFormatter.ofPattern("yyyy/MM"));
        //到目前为止该用户的签到情况，返回十进制数
        List<Long> field = stringRedisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create()
                .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                .valueAt(0));
        if (field == null || field.isEmpty()){
            return Result.ok(0);
        }
        int count = 0;
        //获取签到情况 （十进制）
        Long num = field.get(0);
        if (num == null || num == 0) return Result.ok(0);
        while ((num & 1) == 1){
            count++;
            num >>= 1;
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        //创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
