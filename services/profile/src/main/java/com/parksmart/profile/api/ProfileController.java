package com.parksmart.profile.api;

import com.parksmart.common.security.CurrentAccount;
import com.parksmart.profile.api.dto.Requests;
import com.parksmart.profile.api.dto.Responses.PhotoUploadResponse;
import com.parksmart.profile.api.dto.Responses.ProfileResponse;
import com.parksmart.profile.api.dto.Responses.PublicProfile;
import com.parksmart.profile.application.ProfilePhotoService;
import com.parksmart.profile.application.ProfileService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Every /me endpoint acts on the JWT subject, so ownership is implicit. */
@RestController
@RequestMapping("/api/v1/profiles")
class ProfileController {

  private final ProfileService profiles;
  private final ProfilePhotoService photos;

  ProfileController(ProfileService profiles, ProfilePhotoService photos) {
    this.profiles = profiles;
    this.photos = photos;
  }

  @GetMapping("/me")
  ProfileResponse me() {
    return ProfileResponse.of(profiles.mine(callerId()));
  }

  @PutMapping("/me")
  ProfileResponse update(@Valid @RequestBody Requests.UpdateProfile body) {
    return ProfileResponse.of(
        profiles.update(callerId(), body.displayName(), body.bio(), body.city()));
  }

  @GetMapping("/{accountId}")
  PublicProfile publicProfile(@PathVariable UUID accountId) {
    CurrentAccount.require();
    return PublicProfile.of(profiles.publicProfile(accountId));
  }

  @PostMapping("/me/photo/upload-urls")
  @ResponseStatus(HttpStatus.CREATED)
  PhotoUploadResponse startUpload(@Valid @RequestBody Requests.PhotoUpload body) {
    return PhotoUploadResponse.of(
        photos.startUpload(callerId(), body.contentType(), body.contentLength()));
  }

  @PostMapping("/me/photo/uploads/{uploadId}/confirm")
  ProfileResponse confirmUpload(@PathVariable UUID uploadId) {
    return ProfileResponse.of(photos.confirm(callerId(), uploadId));
  }

  @DeleteMapping("/me/photo")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void deletePhoto() {
    photos.delete(callerId());
  }

  private static UUID callerId() {
    return CurrentAccount.require().accountId();
  }
}
