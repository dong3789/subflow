package com.subflow.api;

import com.subflow.api.dto.ApiDtos.BillingKeyResponse;
import com.subflow.api.dto.ApiDtos.CreateCustomerRequest;
import com.subflow.api.dto.ApiDtos.CustomerResponse;
import com.subflow.api.dto.ApiDtos.RegisterBillingKeyRequest;
import com.subflow.service.CustomerService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 고객·빌링키 API. */
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerResponse register(@Valid @RequestBody CreateCustomerRequest request) {
        return CustomerResponse.of(customerService.register(request.email(), request.name(),
                request.externalKey(), request.projectId()));
    }

    @GetMapping("/{customerId}")
    public CustomerResponse get(@PathVariable Long customerId) {
        return CustomerResponse.of(customerService.get(customerId));
    }

    @GetMapping("/{customerId}/billing-keys")
    public List<BillingKeyResponse> billingKeys(@PathVariable Long customerId) {
        return customerService.billingKeys(customerId).stream().map(BillingKeyResponse::of).toList();
    }

    @PostMapping("/{customerId}/billing-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public BillingKeyResponse registerBillingKey(@PathVariable Long customerId,
                                                 @Valid @RequestBody RegisterBillingKeyRequest request) {
        return BillingKeyResponse.of(customerService.registerBillingKey(customerId,
                request.pgProvider(), request.billingKeyToken(), request.cardLast4()));
    }
}
