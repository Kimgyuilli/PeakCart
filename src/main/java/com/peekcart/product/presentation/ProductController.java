package com.peekcart.product.presentation;

import com.peekcart.global.response.ApiResponse;
import com.peekcart.product.application.ProductQueryService;
import com.peekcart.product.presentation.dto.response.ProductDetailResponse;
import com.peekcart.product.presentation.dto.response.ProductResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 상품 공개 조회 API 엔드포인트.
 * 인증 없이 접근할 수 있다.
 */
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductQueryService productQueryService;

    @GetMapping
    public ResponseEntity<ApiResponse<Page<ProductResponse>>> getProducts(
            @RequestParam(required = false) Long categoryId,
            Pageable pageable
    ) {
        Page<ProductResponse> page = productQueryService.getProducts(categoryId, pageable)
                .map(ProductResponse::from);
        return ResponseEntity.ok(ApiResponse.of(page));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductDetailResponse>> getProduct(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(
                ProductDetailResponse.from(productQueryService.getProduct(id))));
    }
}
