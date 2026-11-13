package com.hodadak.backend.user.infra;

import com.hodadak.backend.user.domain.User;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByKakaoId(String kakaoId);

    // A database upsert also handles simultaneous first logins for the same Kakao account.
    @Modifying
    @Query(value = """
        INSERT INTO users (kakao_id, nickname, role, no_show_count, created_at, updated_at)
        VALUES (:kakaoId, :nickname, 'USER', 0, :now, :now)
        ON CONFLICT (kakao_id) DO NOTHING
        """, nativeQuery = true)
    void insertIfAbsent(@Param("kakaoId") String kakaoId, @Param("nickname") String nickname,
                        @Param("now") Instant now);
}
