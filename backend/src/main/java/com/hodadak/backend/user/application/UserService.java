package com.hodadak.backend.user.application;

import com.hodadak.backend.common.domain.BusinessException;
import com.hodadak.backend.user.domain.User;
import com.hodadak.backend.user.infra.UserRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserService {
    private final UserRepository users;
    private final Clock clock;

    public UserService(UserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    @Transactional
    public UserView login(String kakaoId, String nickname) {
        users.insertIfAbsent(kakaoId, nickname, clock.instant());
        return view(users.findByKakaoId(kakaoId).orElseThrow(BusinessException::unauthorized));
    }

    public UserView get(long id) { return view(find(id)); }

    @Transactional
    public UserView update(long id, String nickname, String phone) {
        User user = find(id);
        user.updateProfile(nickname, phone, clock.instant());
        return view(user);
    }

    private User find(long id) { return users.findById(id).orElseThrow(BusinessException::unauthorized); }
    private UserView view(User user) {
        return new UserView(user.id(), user.nickname(), user.phone(), user.noShowCount());
    }
}
