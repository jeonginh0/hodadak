package com.hodadak.backend.user.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "kakao_id", nullable = false, unique = true, length = 64)
    private String kakaoId;
    @Column(nullable = false, length = 50)
    private String nickname;
    @Column(length = 20)
    private String phone;
    @Column(nullable = false, length = 20)
    private String role;
    @Column(name = "no_show_count", nullable = false)
    private int noShowCount;
    @Column(name = "restricted_until")
    private Instant restrictedUntil;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {}

    public Long id() { return id; }
    public String nickname() { return nickname; }
    public String phone() { return phone; }
    public int noShowCount() { return noShowCount; }

    public void updateProfile(String nickname, String phone, Instant now) {
        if (nickname != null) this.nickname = nickname;
        if (phone != null) this.phone = phone;
        updatedAt = now;
    }
}
