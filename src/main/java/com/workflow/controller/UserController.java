package com.workflow.controller;

import com.workflow.model.User;
import com.workflow.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    public ResponseEntity<List<User>> findAll(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(userService.findAll(user));
    }

    @PostMapping
    public ResponseEntity<User> create(@RequestBody Map<String, Object> body, @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.create(body, user));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<User> update(@PathVariable String id, @RequestBody Map<String, Object> body, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(userService.update(id, body, user));
    }
}
