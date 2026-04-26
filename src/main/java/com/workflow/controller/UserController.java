package com.workflow.controller;

import com.workflow.model.User;
import com.workflow.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    public ResponseEntity<List<User>> findAll() {
        return ResponseEntity.ok(userService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<User> findOne(@PathVariable String id) {
        return ResponseEntity.ok(userService.findOne(id));
    }

    @PostMapping
    public ResponseEntity<User> create(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.create(body));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<User> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(userService.update(id, body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@PathVariable String id) {
        userService.remove(id);
        return ResponseEntity.noContent().build();
    }
}
